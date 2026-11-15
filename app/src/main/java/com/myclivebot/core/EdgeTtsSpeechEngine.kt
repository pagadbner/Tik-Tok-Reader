package com.myclivebot.core

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Native Android client for Microsoft Edge Read Aloud.
 * Uses the current v2 Sec-MS-GEC token and OkHttp WebSocket; no Python or subprocess.
 */
class EdgeTtsSpeechEngine(
    context: Context,
    voiceId: String = EdgeTtsVoiceCatalog.DEFAULT_VOICE_ID,
    volume: Float = 1f,
    speed: Float = 1f
) : SpeechEngine {
    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val lock = Mutex()
    @Volatile private var selectedVoiceId = EdgeTtsVoiceCatalog.find(voiceId).id
    @Volatile private var selectedVolume = volume.coerceIn(0f, 1f)
    @Volatile private var selectedSpeed = speed.coerceIn(0.65f, 1.5f)
    @Volatile private var muted = false
    @Volatile private var activeSocket: WebSocket? = null

    fun update(voiceId: String, volume: Float, speed: Float) {
        selectedVoiceId = EdgeTtsVoiceCatalog.find(voiceId).id
        selectedVolume = volume.coerceIn(0f, 1f)
        selectedSpeed = speed.coerceIn(0.65f, 1.5f)
    }

    fun setMuted(value: Boolean) { muted = value }

    override suspend fun initialize() = Unit

    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        lock.withLock {
            val voice = EdgeTtsVoiceCatalog.find(selectedVoiceId)
            val audio = synthesize(text.take(MAX_TEXT_CHARS), voice.id, voice.id.substringBeforeLast('-'))
            ApiAudioPlayer.play(appContext, audio, selectedVolume, selectedSpeed, muted)
        }
    }

    private suspend fun synthesize(text: String, voice: String, locale: String): ByteArray =
        withTimeout(EDGE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val requestId = UUID.randomUUID().toString().replace("-", "")
                val connectionId = UUID.randomUUID().toString().replace("-", "")
                val token = EdgeSecMsGec.generate()
                val query = "TrustedClientToken=$TRUSTED_TOKEN" +
                    "&Sec-MS-GEC=${urlEncode(token)}" +
                    "&Sec-MS-GEC-Version=${urlEncode(SEC_GEC_VERSION)}" +
                    "&ConnectionId=$connectionId"
                val request = Request.Builder()
                    .url("$WEBSOCKET_URL?$query")
                    .header("Origin", ORIGIN)
                    .header("User-Agent", USER_AGENT)
                    .build()
                val audio = ByteArrayOutputStream()
                var completed = false
                var socket: WebSocket? = null

                fun finish(error: Throwable? = null) {
                    if (completed) return
                    completed = true
                    activeSocket = null
                    if (error == null) {
                        val bytes = audio.toByteArray()
                        if (bytes.isEmpty()) {
                            if (continuation.isActive) continuation.resumeWithException(IOException("Edge-TTS no devolvió audio."))
                        } else if (continuation.isActive) continuation.resume(bytes)
                    } else if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }

                continuation.invokeOnCancellation {
                    socket?.cancel()
                }

                socket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        activeSocket = webSocket
                        val config = """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                        webSocket.send(protocolMessage(requestId, "application/json; charset=utf-8", "speech.config", config))
                        val rate = ((selectedSpeed - 1f) * 100f).toInt()
                        val rateText = if (rate >= 0) "+$rate%" else "$rate%"
                        val escaped = escapeXml(text)
                        val ssml = """<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="$locale"><voice name="$voice"><prosody rate="$rateText" pitch="+0Hz" volume="100%">$escaped</prosody></voice></speak>"""
                        val timestamp = Instant.now().toString()
                        val frame = "X-RequestId:$requestId\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:$timestamp\r\nPath:ssml\r\n\r\n$ssml"
                        webSocket.send(frame)
                    }

                    override fun onMessage(webSocket: WebSocket, textMessage: String) {
                        if (textMessage.contains("Path:turn.end", ignoreCase = true)) {
                            webSocket.close(1000, "done")
                            finish()
                        } else if (textMessage.contains("Path:response", ignoreCase = true) &&
                            (textMessage.contains("Forbidden", true) || textMessage.contains("error", true))) {
                            finish(IOException("Edge-TTS rechazó la solicitud: HTTP/protocolo no autorizado."))
                            webSocket.cancel()
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val packet = bytes.toByteArray()
                        if (packet.size < 3) return
                        val headerLength = ((packet[0].toInt() and 0xff) shl 8) or (packet[1].toInt() and 0xff)
                        if (headerLength <= 0 || headerLength > packet.size - 2) return
                        val header = packet.copyOfRange(2, 2 + headerLength).toString(Charsets.UTF_8)
                        if (header.contains("Path:audio", ignoreCase = true)) {
                            audio.write(packet, 2 + headerLength, packet.size - 2 - headerLength)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val status = response?.code
                        finish(IOException(if (status != null) "Edge-TTS respondió con HTTP $status." else "No se pudo conectar con Edge-TTS: ${t.message ?: "error de red"}.", t))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (!completed) {
                            if (audio.size() > 0) finish()
                            else finish(IOException("Edge-TTS cerró la conexión antes de generar audio ($code)."))
                        }
                    }
                })
            }
        }

    private fun protocolMessage(id: String, contentType: String, path: String, body: String) =
        "X-RequestId:$id\r\nContent-Type:$contentType\r\nPath:$path\r\n\r\n$body"

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    override suspend fun stop() { activeSocket?.cancel() }

    fun release() {
        activeSocket?.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val SEC_GEC_VERSION = "2-155.0.4283.45"
        private const val WEBSOCKET_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36 Edg/155.0.0.0"
        private const val EDGE_TIMEOUT_MS = 40_000L
        private const val MAX_TEXT_CHARS = 500
    }
}

/** Sec-MS-GEC v2: CS64 digest + SHA-256, both over the five-minute Windows epoch token. */
internal object EdgeSecMsGec {
    private const val MASK = 0xffffffffL
    private const val WINDOWS_EPOCH_SECONDS = 11_644_473_600L
    private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val FACTOR = 0x82893933L
    private const val ADJUSTMENT0 = 0xF2F90B1AL
    private const val ADJUSTMENT1 = 0x0B1A0000L
    private val coefficients = longArrayOf(
        0xE82E91B1L,0x44DE24C6L,0x3BEB65BCL,0xC89C5933L,0x8D1EC211L,0x314EF5D5L,0xE92491B1L,
        0xF0A5B9D5L,0x06064CF3L,0xDA79FA60L,0xBED20560L,0x89F9C9F8L,0x35FA38C6L,0xCB49E3C6L
    )
    private val offsets = longArrayOf(
        0xB32700D0L,0x88DFF51DL,0x29514038L,0xB328CBDFL,0xD405B4D5L,0x26E55F3CL,0x607801C6L,
        0x82B2C1FBL,0xFA51A3DAL,0x207CC464L,0x3C3C74F3L,0xD2D14B61L,0xBB04A2C1L,0xC5626167L
    )

    fun generate(nowSeconds: Long = System.currentTimeMillis() / 1000L): String {
        val rounded = ((nowSeconds + WINDOWS_EPOCH_SECONDS) / 300L) * 300L
        val ticks = rounded * 10_000_000L
        val data = "${ticks}$TRUSTED_TOKEN".toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val keyA = (readLeInt(digest, 0) or 1L) and MASK
        val keyB = (readLeInt(digest, 4) or 1L) and MASK
        val factorA = (keyA * FACTOR) and MASK
        val factorB = (keyB * FACTOR) and MASK

        val terms = ArrayList<Long>()
        val offs = ArrayList<Long>()
        for (i in coefficients.indices) {
            terms.add((coefficients[i] * if (i % 2 == 0) factorA else factorB) and MASK)
            offs.add(offsets[i])
        }

        val words = wordsLittleEndianEven(byteArrayOf(0) + data)
        words.forEachIndexed { index, word ->
            var term = word * if (index % 2 == 0) keyA else keyB
            var offset = rol16(word) * if (index % 2 == 0) 0xEF0569FBL else 0xC31713DBL
            if (index == 0) {
                term += ADJUSTMENT0 * factorA
                offset += ADJUSTMENT1
            }
            terms.add(term and MASK)
            offs.add(offset and MASK)
        }

        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L
        var index = 0
        while (index < terms.size) {
            val x = terms[index]
            val y = terms[index + 1]
            var t = rol16((offs[index] + x) and MASK)
            var q = (t * 0x76F50F34L) and MASK
            swapAcc = (swapAcc + ((rol16(q) * 0xC3EFEA97L + q) and MASK)) and MASK
            t = rol16((offs[index + 1] + y) and MASK)
            q = (t * 0x1881CE3CL) and MASK
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and MASK
            swapAcc = (swapAcc + swapLast) and MASK

            state = (state * keyA + x) and MASK
            state = (rol16(state) * 0xCF98B111L) and MASK
            state = (rol16(state) * 0x87085B9FL) and MASK
            state = (rol16(state) * 0x12CEB96DL) and MASK
            state = (rol16(state) * 0x257E1D83L) and MASK
            acc = (acc + state) and MASK

            t = (state * keyB + y) and MASK
            t = (rol16(t) * 0xA27416F5L) and MASK
            t = (rol16(t) * 0xD38396FFL) and MASK
            state = (rol16((rol16(t) * 0x7C932B89L) and MASK) * 0xBFA49F69L) and MASK
            acc = (acc + state) and MASK
            index += 2
        }

        val first = swapLast xor state
        val second = swapAcc xor acc
        val cs64Bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(first.toInt()).putInt(second.toInt()).array()
        return "${cs64Bytes.toHexUpper()}-${digest.toHexUpper()}"
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)) and MASK

    private fun wordsLittleEndianEven(bytes: ByteArray): List<Long> {
        val paddedSize = ((bytes.size + 3) / 4) * 4
        val padded = bytes.copyOf(paddedSize)
        val words = ArrayList<Long>()
        var i = 0
        while (i < padded.size) {
            words.add(readLeInt(padded, i))
            i += 4
        }
        if (words.size % 2 != 0) words.add(0L)
        return words
    }

    private fun rol16(value: Long): Long =
        (((value and MASK) shl 16) or ((value and MASK) ushr 16)) and MASK

    private fun ByteArray.toHexUpper(): String {
        val chars = "0123456789ABCDEF"
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xff
            out.append(chars[value ushr 4]).append(chars[value and 0x0f])
        }
        return out.toString()
    }
}
