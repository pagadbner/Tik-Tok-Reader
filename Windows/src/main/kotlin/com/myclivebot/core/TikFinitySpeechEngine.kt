package com.myclivebot.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Remote TikFinity TTS provider adapted to the Windows/JVM MP3 output. */
class TikFinitySpeechEngine(
    voiceId: String = TikFinityVoiceCatalog.DEFAULT_VOICE_ID,
    channelId: String = "0",
    volume: Float = 1f,
    speed: Float = 1f
) : SpeechEngine {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val playbackLock = Mutex()
    @Volatile private var selectedVoiceId = TikFinityVoiceCatalog.find(voiceId).id
    @Volatile private var selectedChannelId = channelId
    @Volatile private var selectedVolume = volume.coerceIn(0f, 1f)
    @Volatile private var selectedSpeed = speed.coerceIn(0.65f, 1.5f)
    @Volatile private var muted = false
    @Volatile private var activeCall: okhttp3.Call? = null

    fun update(voiceId: String, channelId: String, volume: Float, speed: Float) {
        selectedVoiceId = TikFinityVoiceCatalog.find(voiceId).id
        selectedChannelId = channelId.trim().ifBlank { "0" }
        selectedVolume = volume.coerceIn(0f, 1f)
        selectedSpeed = speed.coerceIn(0.65f, 1.5f)
    }

    fun setMuted(value: Boolean) { muted = value }

    override suspend fun initialize() = Unit

    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        playbackLock.withLock {
            val voice = selectedVoiceId
            val channel = selectedChannelId.toULongOrNull()?.toString()
                ?: throw IOException("El ID del canal de TikFinity debe ser numérico.")
            val audio = withContext(Dispatchers.IO) { requestAudio(text.take(280), voice, channel) }
            withContext(Dispatchers.IO) {
                DesktopAudioPlayer.play(audio, selectedVolume, selectedSpeed, muted)
            }
        }
    }

    private fun requestAudio(text: String, voice: String, channel: String): ByteArray {
        val query = "voice=${urlQueryEscape(voice)}" +
            "&text=${urlQueryEscape(text)}" +
            "&isWidget=false" +
            "&cid=${urlQueryEscape(channel)}"
        val tokenValue = tikFinityToken(query)
        val fullUrl = "$API_URL?$query&token=$tokenValue"
        val request = Request.Builder().url(fullUrl).get().header("Accept", "audio/mpeg, audio/mp3").build()
        val call = client.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                if (response.code != 200) throw IOException("TikFinity respondió con HTTP ${response.code}.")
                val body = response.body ?: throw IOException("TikFinity no devolvió audio.")
                val contentType = body.contentType()?.let { "${it.type}/${it.subtype}".lowercase() }.orEmpty()
                if (contentType !in setOf("audio/mpeg", "audio/mp3")) {
                    throw IOException("TikFinity no devolvió un MP3 válido (Content-Type: ${contentType.ifBlank { "desconocido" }}).")
                }
                if (body.contentLength() > MAX_AUDIO_BYTES) throw IOException("El audio de TikFinity supera el límite permitido.")
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_AUDIO_BYTES) throw IOException("El audio de TikFinity supera el límite permitido.")
                        output.write(buffer, 0, count)
                    }
                }
                val bytes = output.toByteArray()
                if (bytes.isEmpty()) throw IOException("TikFinity devolvió un audio vacío.")
                if (bytes.size < 3 || bytes[0] != 0x49.toByte() && bytes[0] != 0xFF.toByte()) {
                    throw IOException("El contenido recibido no parece ser un MP3 válido.")
                }
                return bytes
            }
        } catch (error: IOException) {
            if (call.isCanceled()) {
                throw CancellationException("Solicitud de TikFinity cancelada.").apply { initCause(error) }
            }
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

    private fun urlQueryEscape(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun tikFinityToken(query: String): String {
        val input = query + "_audio"
        var hash = 0
        input.forEach { character -> hash = hash * 31 + character.code }
        val number = abs(hash.toLong() * 257533L)
        return number.toString().take(6)
    }

    companion object {
        private const val API_URL = "https://tikfinity-tts-api.zerody.one/api/voice/generate"
        private const val MAX_AUDIO_BYTES = 10 * 1024 * 1024
    }
}
