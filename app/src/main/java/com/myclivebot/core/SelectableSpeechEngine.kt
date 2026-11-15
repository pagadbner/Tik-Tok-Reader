package com.myclivebot.core

import android.content.Context
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/**
 * One stable SpeechEngine for SpeechQueueWorker. Provider changes never create
 * a second queue and do not touch the TikTok LIVE connection.
 */
class SelectableSpeechEngine(context: Context, initial: ReaderSettings, private val onApiError: (Throwable) -> Unit = {}) : SpeechEngine {
    private val appContext = context.applicationContext
    private val settings = AtomicReference(initial)
    private val piper = DynamicPiperSpeechEngine(appContext, initial)
    private val tikFinity = TikFinitySpeechEngine(
        appContext,
        initial.apiVoiceId,
        initial.tikFinityChannelId,
        initial.volume,
        initial.speed
    )

    fun update(next: ReaderSettings) {
        settings.set(next)
        piper.update(next)
        tikFinity.update(next.apiVoiceId, next.tikFinityChannelId, next.volume, next.speed)
    }

    fun setMuted(value: Boolean) {
        piper.setMuted(value)
        tikFinity.setMuted(value)
    }

    override suspend fun initialize() {
        val current = settings.get()
        if (current.speechProvider == PROVIDER_TIKFINITY) tikFinity.initialize()
        else piper.initialize()
    }

    override suspend fun speak(text: String) {
        val current = settings.get()
        if (current.speechProvider == PROVIDER_TIKFINITY) {
            val voiceId = if (current.apiRandomVoice) TikFinityVoiceCatalog.randomVoice() else current.apiVoiceId
            tikFinity.update(voiceId, current.tikFinityChannelId, current.volume, current.speed)
            try {
                tikFinity.speak(text)
            } catch (error: Exception) {
                if (error !is CancellationException) onApiError(error)
                throw error
            }
        } else {
            piper.speak(text)
        }
    }

    override suspend fun stop() {
        runCatching { piper.stop() }
        runCatching { tikFinity.stop() }
    }

    fun release() {
        piper.release()
    }

    companion object {
        const val PROVIDER_PIPER = "piper"
        const val PROVIDER_TIKFINITY = "tikfinity"
    }
}
