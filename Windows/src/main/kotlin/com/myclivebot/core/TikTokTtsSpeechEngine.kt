package com.myclivebot.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Remote TikTok TTS adapted to the Windows/JVM audio output. */
class TikTokTtsSpeechEngine(
    voiceId: String = TikTokTtsVoiceCatalog.DEFAULT_VOICE_ID,
    volume: Float = 1f,
    speed: Float = 1f
) : SpeechEngine {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val lock = Mutex()
    @Volatile private var selectedVoiceId = TikTokTtsVoiceCatalog.find(voiceId).id
    @Volatile private var selectedVolume = volume.coerceIn(0f, 1f)
    @Volatile private var selectedSpeed = speed.coerceIn(0.65f, 1.5f)
    @Volatile private var muted = false
    @Volatile private var activeCall: okhttp3.Call? = null

    fun update(voiceId: String, volume: Float, speed: Float) {
        selectedVoiceId = TikTokTtsVoiceCatalog.find(voiceId).id
        selectedVolume = volume.coerceIn(0f, 1f)
        selectedSpeed = speed.coerceIn(0.65f, 1.5f)
    }

    fun setMuted(value: Boolean) { muted = value }

    override suspend fun initialize() = Unit

    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        lock.withLock {
            val voice = selectedVoiceId
            val safeText = limitUtf8(text, MAX_TEXT_BYTES)
            val audio = withContext(Dispatchers.IO) { requestAudio(safeText, voice) }
            withContext(Dispatchers.IO) {
                DesktopAudioPlayer.play(audio, selectedVolume, selectedSpeed, muted)
            }
        }
    }

    private fun requestAudio(text: String, voice: String): ByteArray {
        val json = JSONObject().put("text", text).put("voice", voice).toString()
        val body = json.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(API_URL)
            .post(body)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .build()
        val call = client.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("TikTok TTS respondió con HTTP ${response.code}.")
                val responseBody = response.body ?: throw IOException("TikTok TTS no devolvió contenido.")
                if (responseBody.contentLength() > MAX_RESPONSE_CHARS.toLong()) {
                    throw IOException("La respuesta de TikTok TTS supera el límite permitido.")
                }
                val raw = responseBody.string()
                if (raw.isBlank() || raw.length > MAX_RESPONSE_CHARS) {
                    throw IOException("Respuesta vacía o demasiado grande de TikTok TTS.")
                }
                val result = try { JSONObject(raw) } catch (_: Exception) {
                    throw IOException("TikTok TTS devolvió una respuesta no válida.")
                }
                if (!result.optBoolean("success", false)) {
                    val detail = result.optString("error").take(160)
                    throw IOException(detail.ifBlank { "TikTok TTS no pudo generar el audio." })
                }
                val encoded = result.optString("data")
                if (encoded.isBlank()) throw IOException("TikTok TTS no incluyó el audio.")
                val decoded = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
                    throw IOException("TikTok TTS devolvió audio Base64 no válido.")
                }
                if (decoded.isEmpty() || decoded.size > MAX_AUDIO_BYTES) {
                    throw IOException("El audio de TikTok TTS está vacío o supera el límite.")
                }
                if (!looksLikeMp3(decoded)) throw IOException("TikTok TTS no devolvió un MP3 reconocible.")
                return decoded
            }
        } catch (error: IOException) {
            if (call.isCanceled()) throw CancellationException("Solicitud de TikTok TTS cancelada.").apply { initCause(error) }
            throw error
        } finally {
            activeCall = null
        }
    }

    override suspend fun stop() {
        activeCall?.cancel()
        withContext(Dispatchers.IO) { DesktopAudioPlayer.stop() }
    }

    fun release() {
        activeCall?.cancel()
        DesktopAudioPlayer.stop()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun limitUtf8(value: String, maxBytes: Int): String {
        val out = StringBuilder()
        var bytes = 0
        value.codePoints().forEach { cp ->
            val piece = String(Character.toChars(cp))
            val count = piece.toByteArray(Charsets.UTF_8).size
            if (bytes + count <= maxBytes) { out.append(piece); bytes += count }
        }
        return out.toString()
    }

    private fun looksLikeMp3(bytes: ByteArray): Boolean =
        bytes.size >= 3 && (
            bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() && bytes[2] == 0x33.toByte() ||
                bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0
            )

    companion object {
        private const val API_URL = "https://ottsy.weilbyte.dev/api/generation"
        private const val MAX_TEXT_BYTES = 300
        private const val MAX_AUDIO_BYTES = 10 * 1024 * 1024
        private const val MAX_RESPONSE_CHARS = 15 * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
