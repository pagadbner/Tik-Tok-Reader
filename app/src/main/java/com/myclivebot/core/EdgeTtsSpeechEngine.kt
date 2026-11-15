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
                val query = "Ocp-Apim-Subscription-Key=$TRUSTED_TOKEN" +
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
                if (!continuation.isActive) socket?.cancel()
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
        private const val WEBSOCKET_URL = "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1"
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

    private val shaInitial = longArrayOf(
        0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
        0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L
    )
    private val shaPrecomputedState = longArrayOf(
        0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
        0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L
    )
    private val shaSchedule = longArrayOf(
        0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
        0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
        0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
        0xD258B060L, 0x72766564L
    )
    private val shaK = longArrayOf(
        0x428A2F98L,0x71374491L,0xB5C0FBCFL,0xE9B5DBA5L,0x3956C25BL,0x59F111F1L,0x923F82A4L,0xAB1C5ED5L,
        0xD807AA98L,0x12835B01L,0x243185BEL,0x550C7DC3L,0x72BE5D74L,0x80DEB1FEL,0x9BDC06A7L,0xC19BF174L,
        0xE49B69C1L,0xEFBE4786L,0x0FC19DC6L,0x240CA1CCL,0x2DE92C6FL,0x4A7484AAL,0x5CB0A9DCL,0x76F988DAL,
        0x983E5152L,0xA831C66DL,0xB00327C8L,0xBF597FC7L,0xC6E00BF3L,0xD5A79147L,0x06CA6351L,0x14292967L,
        0x27B70A85L,0x2E1B2138L,0x4D2C6DFCL,0x53380D13L,0x650A7354L,0x766A0ABBL,0x81C2C92EL,0x92722C85L,
        0xA2BFE8A1L,0xA81A664BL,0xC24B8B70L,0xC76C51A3L,0xD192E819L,0xD6990624L,0xF40E3585L,0x106AA070L,
        0x19A4C116L,0x1E376C08L,0x2748774CL,0x34B0BCB5L,0x391C0CB3L,0x4ED8AA4AL,0x5B9CCA4FL,0x682E6FF3L,
        0x748F82EEL,0x78A5636FL,0x84C87814L,0x8CC70208L,0x90BEFFFAL,0xA4506CEBL,0xBEF9A3F7L,0xC67178F2L
    )
    private val shaBlockK = shaK.copyOf().apply { this[14] = 0xC9DC06A7L }

    private val csCoefficients = longArrayOf(
        0xE82E91B1L,0x44DE24C6L,0x3BEB65BCL,0xC89C5933L,0x8D1EC211L,0x314EF5D5L,0xE92491B1L,
        0xF0A5B9D5L,0x06064CF3L,0xDA79FA60L,0xBED20560L,0x89F9C9F8L,0x35FA38C6L,0xCB49E3C6L
    )
    private val csOffsets = longArrayOf(
        0xB32700D0L,0x88DFF51DL,0x29514038L,0xB328CBDFL,0xD405B4D5L,0x26E55F3CL,0x607801C6L,
        0x82B2C1FBL,0xFA51A3DAL,0x207CC464L,0x3C3C74F3L,0xD2D14B61L,0xBB04A2C1L,0xC5626167L
    )

    fun generate(nowSeconds: Long = System.currentTimeMillis() / 1000L): String {
        val rounded = ((nowSeconds + WINDOWS_EPOCH_SECONDS) / 300L) * 300L
        val ticks = rounded * 10_000_000L
        val data = "${ticks}$TRUSTED_TOKEN".toByteArray(Charsets.US_ASCII)
        val digest = customSha256(data)
        val keyA = (readLittleEndian(digest, 0) or 1L) and MASK
        val keyB = (readLittleEndian(digest, 4) or 1L) and MASK
        val factorA = (keyA * FACTOR) and MASK
        val factorB = (keyB * FACTOR) and MASK

        val terms = ArrayList<Long>()
        val offsets = ArrayList<Long>()
        for (i in csCoefficients.indices) {
            terms.add((csCoefficients[i] * if (i % 2 == 0) factorA else factorB) and MASK)
            offsets.add(csOffsets[i])
        }
        littleEndianWordsEven(byteArrayOf(0) + data).forEachIndexed { index, word ->
            var term = word * if (index % 2 == 0) keyA else keyB
            var offset = rol16(word) * if (index % 2 == 0) 0xEF0569FBL else 0xC31713DBL
            if (index == 0) {
                term += ADJUSTMENT0 * factorA
                offset += ADJUSTMENT1
            }
            terms.add(term and MASK)
            offsets.add(offset and MASK)
        }

        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L
        var i = 0
        while (i < terms.size) {
            val x = terms[i]
            val y = terms[i + 1]
            var t = rol16((offsets[i] + x) and MASK)
            var q = (t * 0x76F50F34L) and MASK
            swapAcc = (swapAcc + ((rol16(q) * 0xC3EFEA97L + q) and MASK)) and MASK
            t = rol16((offsets[i + 1] + y) and MASK)
            q = (t * 0x1881CE3CL) and MASK
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and MASK
            swapAcc = (swapAcc + swapLast) and MASK

            state = (state * keyA + x) and MASK
            state = (rol16(state) * 0xCF98B111L) and MASK
            state = (rol16(state) * 0x87085B9FL) and MASK
            state = (rol16(state) * 0x12CEB96DL) and MASK
            state = (rol16(state) * 0x257E1D83L) and MASK
            acc += state
            t = (state * keyB + y) and MASK
            t = (rol16(t) * 0xA27416F5L) and MASK
            t = (rol16(t) * 0xD38396FFL) and MASK
            state = (rol16((rol16(t) * 0x7C932B89L) and MASK) * 0xBFA49F69L) and MASK
            acc = (acc + state) and MASK
            i += 2
        }

        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt((swapLast xor state).toInt())
            .putInt((swapAcc xor acc).toInt())
            .array()
        return bytes.toHexUpper() + "-" + digest.toHexUpper()
    }

    /** Faithful SHA-256 specialization used by Microsoft's v2 CS64 token algorithm. */
    private fun customSha256(data: ByteArray): ByteArray {
        require(data.size >= 7)
        val length = 57 + data.size
        val zeroCount = ((55 - length) % 64 + 64) % 64
        val tail = data.copyOfRange(7, data.size)
        val padded = tail + byteArrayOf(0x80.toByte()) + ByteArray(zeroCount) +
            ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(length.toLong() * 8L).array()
        var state = initializeShaState(data.copyOfRange(0, 7))
        var offset = 0
        while (offset < padded.size) {
            val words = LongArray(64)
            for (word in 0 until 16) words[word] = readBigEndian(padded, offset + word * 4, 4)
            expandSchedule(words, 16)
            val working = compress(state, words, 0, shaK)
            state = LongArray(8) { (state[it] + working[it]) and MASK }
            offset += 64
        }
        return ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
            state.forEach { putInt(it.toInt()) }
        }.array()
    }

    private fun initializeShaState(firstSeven: ByteArray): LongArray {
        val words = LongArray(64)
        words[14] = readBigEndian(firstSeven, 0, 3)
        words[15] = readBigEndian(firstSeven, 3, 4)
        for (i in 16..29) {
            var value = shaSchedule[i - 16]
            if (i - 2 >= 14) {
                val sigma = smallSigma1(words[i - 2])
                value += if (i == 16) sigma xor 0x000B92C0L else sigma
            }
            if (i - 7 >= 14) value += words[i - 7]
            if (i - 15 >= 14) {
                val sigma = smallSigma0(words[i - 15])
                value += if (i == 29) sigma xor 0x059C0B80L else sigma
            }
            words[i] = value and MASK
        }
        words[30] = (
            smallSigma1(words[28]) + words[23] + smallSigma0(words[15]) +
                words[14] + 0xC9DC06A7L - shaK[14]
            ) and MASK
        expandSchedule(words, 31)
        val working = compress(shaPrecomputedState, words, 14, shaBlockK)
        return LongArray(8) { (shaInitial[it] + working[it]) and MASK }
    }

    private fun expandSchedule(words: LongArray, first: Int) {
        for (i in first until 64) {
            words[i] = (smallSigma1(words[i - 2]) + words[i - 7] +
                smallSigma0(words[i - 15]) + words[i - 16]) and MASK
        }
    }

    private fun compress(state: LongArray, words: LongArray, firstRound: Int, constants: LongArray): LongArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in firstRound until 64) {
            val sum1 = bigSigma1(e)
            val choose = (e and f) xor (e.inv() and g)
            val t1 = (h + sum1 + choose + constants[i] + words[i]) and MASK
            val sum0 = bigSigma0(a)
            val majority = (a and b) xor (a and c) xor (b and c)
            val t2 = (sum0 + majority) and MASK
            h = g; g = f; f = e; e = (d + t1) and MASK
            d = c; c = b; b = a; a = (t1 + t2) and MASK
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun bigSigma0(x: Long) = rotr(x, 2) xor rotr(x, 13) xor rotr(x, 22)
    private fun bigSigma1(x: Long) = rotr(x, 6) xor rotr(x, 11) xor rotr(x, 25)
    private fun smallSigma0(x: Long) = rotr(x, 7) xor rotr(x, 18) xor (x ushr 3)
    private fun smallSigma1(x: Long) = rotr(x, 17) xor rotr(x, 19) xor (x ushr 10)
    private fun rotr(x: Long, n: Int): Long = ((x ushr n) or (x shl (32 - n))) and MASK

    private fun readBigEndian(bytes: ByteArray, offset: Int, count: Int): Long {
        var result = 0L
        for (i in 0 until count) result = (result shl 8) or (bytes[offset + i].toLong() and 0xffL)
        return result and MASK
    }

    private fun readLittleEndian(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)) and MASK

    private fun littleEndianWordsEven(bytes: ByteArray): List<Long> {
        val padded = bytes.copyOf(((bytes.size + 3) / 4) * 4)
        val words = ArrayList<Long>()
        var i = 0
        while (i < padded.size) {
            words.add(readLittleEndian(padded, i))
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
