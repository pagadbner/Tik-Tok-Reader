package com.myclivebot.core

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Remote TikFinity TTS provider. It is deliberately isolated from Piper's
 * model files, catalog and synthesis implementation.
 */
class TikFinitySpeechEngine(
    context: Context,
    voiceId: String = TikFinityVoiceCatalog.DEFAULT_VOICE_ID,
    channelId: String = "0",
    volume: Float = 1f,
    speed: Float = 1f
) : SpeechEngine {
    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    private val playbackLock = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var selectedVoiceId = voiceId
    @Volatile private var selectedChannelId = channelId
    @Volatile private var selectedVolume = volume.coerceIn(0f, 1f)
    @Volatile private var selectedSpeed = speed.coerceIn(0.65f, 1.5f)
    @Volatile private var muted = false
    @Volatile private var activePlayer: MediaPlayer? = null
    @Volatile private var activeCall: okhttp3.Call? = null
    @Volatile private var stopPlayback: (() -> Unit)? = null

    fun update(voiceId: String, channelId: String, volume: Float, speed: Float) {
        selectedVoiceId = TikFinityVoiceCatalog.find(voiceId).id
        selectedChannelId = channelId.trim().ifBlank { "0" }
        selectedVolume = volume.coerceIn(0f, 1f)
        selectedSpeed = speed.coerceIn(0.65f, 1.5f)
        activePlayer?.let { player ->
            runCatching { player.setVolume(if (muted) 0f else selectedVolume, if (muted) 0f else selectedVolume) }
        }
    }

    fun setMuted(value: Boolean) {
        muted = value
        val level = if (value) 0f else selectedVolume
        activePlayer?.let { player -> runCatching { player.setVolume(level, level) } }
    }

    override suspend fun initialize() {
        // The remote service is intentionally contacted only when an utterance is requested.
    }

    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        playbackLock.withLock {
            val voice = selectedVoiceId
            val channel = selectedChannelId.toULongOrNull()?.toString()
                ?: throw IOException("El ID del canal de TikFinity debe ser numérico.")
            val audio = withContext(Dispatchers.IO) { requestAudio(text.take(280), voice, channel) }
            playAudio(audio)
        }
    }

    private fun requestAudio(text: String, voice: String, channel: String): File {
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
                return File.createTempFile("tikfinity-tts-", ".mp3", appContext.cacheDir).apply {
                    writeBytes(bytes)
                }
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

    private suspend fun playAudio(file: File) {
        try {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val player = MediaPlayer()
                    activePlayer = player
                    var completed = false
                    fun finish(error: Throwable? = null) {
                        if (completed) return
                        completed = true
                        activePlayer = null
                        stopPlayback = null
                        runCatching { player.reset() }
                        runCatching { player.release() }
                        if (continuation.isActive) {
                            if (error == null) continuation.resume(Unit)
                            else continuation.resumeWithException(error)
                        }
                    }
                    stopPlayback = { finish() }
                    continuation.invokeOnCancellation {
                        mainHandler.post { finish() }
                    }
                    try {
                        player.setDataSource(file.absolutePath)
                        val level = if (muted) 0f else selectedVolume
                        player.setVolume(level, level)
                        player.setOnCompletionListener { finish() }
                        player.setOnErrorListener { _, what, extra ->
                            finish(IOException("No se pudo reproducir el audio de TikFinity ($what/$extra)."))
                            true
                        }
                        player.setOnPreparedListener { prepared ->
                            runCatching {
                                prepared.playbackParams = prepared.playbackParams.setSpeed(selectedSpeed)
                            }
                            prepared.start()
                        }
                        player.prepareAsync()
                    } catch (error: Exception) {
                        finish(error)
                    }
                }
            }
        } finally {
            withContext(Dispatchers.IO) { runCatching { file.delete() } }
        }
    }

    override suspend fun stop() {
        activeCall?.cancel()
        val cancelPlayback = {
            val stop = stopPlayback
            if (stop != null) stop() else {
                val player = activePlayer
                activePlayer = null
                if (player != null) {
                    runCatching { player.stop() }
                    runCatching { player.release() }
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cancelPlayback()
        else mainHandler.post { cancelPlayback() }
    }

    private fun urlQueryEscape(value: String): String =
        URLEncoder.encode(value, "UTF-8")

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
