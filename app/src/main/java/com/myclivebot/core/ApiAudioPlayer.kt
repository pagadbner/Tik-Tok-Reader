package com.myclivebot.core

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal object ApiAudioPlayer {
    suspend fun play(context: Context, audio: ByteArray, volume: Float, speed: Float, muted: Boolean) {
        if (audio.isEmpty()) throw IOException("El proveedor no devolvió audio.")
        val file = withContext(Dispatchers.IO) {
            File.createTempFile("api-tts-", ".mp3", context.cacheDir).apply { writeBytes(audio) }
        }
        try {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val player = MediaPlayer()
                    var finished = false
                    fun finish(error: Throwable? = null) {
                        if (finished) return
                        finished = true
                        runCatching { player.reset() }
                        runCatching { player.release() }
                        if (continuation.isActive) {
                            if (error == null) continuation.resume(Unit)
                            else continuation.resumeWithException(error)
                        }
                    }
                    continuation.invokeOnCancellation {
                        Handler(Looper.getMainLooper()).post { finish() }
                    }
                    try {
                        player.setDataSource(file.absolutePath)
                        val level = if (muted) 0f else volume.coerceIn(0f, 1f)
                        player.setVolume(level, level)
                        player.setOnCompletionListener { finish() }
                        player.setOnErrorListener { _, what, extra ->
                            finish(IOException("No se pudo reproducir el MP3 ($what/$extra)."))
                            true
                        }
                        player.setOnPreparedListener { prepared ->
                            try {
                                runCatching {
                                    prepared.playbackParams = prepared.playbackParams.setSpeed(speed.coerceIn(0.65f, 1.5f))
                                }
                                prepared.start()
                            } catch (error: Exception) {
                                finish(error)
                            }
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
}
