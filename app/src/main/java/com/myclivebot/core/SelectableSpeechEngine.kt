package com.myclivebot.core

import android.content.Context
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/** One stable engine for the existing FIFO queue. Switching provider never reconnects LIVE. */
class SelectableSpeechEngine(
    context: Context,
    initial: ReaderSettings,
    private val onApiError: (Throwable) -> Unit = {}
) : SpeechEngine {
    private val appContext = context.applicationContext
    private val settings = AtomicReference(initial)
    private val piper = DynamicPiperSpeechEngine(appContext, initial)
    private val tikFinity = TikFinitySpeechEngine(appContext, initial.apiVoiceId, initial.tikFinityChannelId, initial.volume, initial.speed)
    private val edge = EdgeTtsSpeechEngine(appContext, initial.edgeVoiceId, initial.volume, initial.speed)
    private val tikTok = TikTokTtsSpeechEngine(appContext, initial.tikTokVoiceId, initial.volume, initial.speed)

    fun update(next: ReaderSettings) {
        settings.set(next)
        piper.update(next)
        tikFinity.update(next.apiVoiceId, next.tikFinityChannelId, next.volume, next.speed)
        edge.update(next.edgeVoiceId, next.volume, next.speed)
        tikTok.update(next.tikTokVoiceId, next.volume, next.speed)
    }

    fun setMuted(value: Boolean) {
        piper.setMuted(value)
        tikFinity.setMuted(value)
        edge.setMuted(value)
        tikTok.setMuted(value)
    }

    override suspend fun initialize() {
        when (settings.get().speechProvider) {
            PROVIDER_TIKFINITY -> tikFinity.initialize()
            PROVIDER_EDGE -> edge.initialize()
            PROVIDER_TIKTOK -> tikTok.initialize()
            else -> piper.initialize()
        }
    }

    override suspend fun speak(text: String) {
        val current = settings.get()
        try {
            when (current.speechProvider) {
                PROVIDER_TIKFINITY -> {
                    val voice = if (current.apiRandomVoice) TikFinityVoiceCatalog.randomVoice() else current.apiVoiceId
                    tikFinity.update(voice, current.tikFinityChannelId, current.volume, current.speed)
                    tikFinity.speak(text)
                }
                PROVIDER_EDGE -> {
                    edge.update(current.edgeVoiceId, current.volume, current.speed)
                    edge.speak(text)
                }
                PROVIDER_TIKTOK -> {
                    tikTok.update(current.tikTokVoiceId, current.volume, current.speed)
                    tikTok.speak(text)
                }
                else -> piper.speak(text)
            }
        } catch (error: Exception) {
            if (current.speechProvider != PROVIDER_PIPER && error !is CancellationException) onApiError(error)
            throw error
        }
    }

    override suspend fun stop() {
        runCatching { piper.stop() }
        runCatching { tikFinity.stop() }
        runCatching { edge.stop() }
        runCatching { tikTok.stop() }
    }

    fun release() {
        piper.release()
        tikFinity.release()
        edge.release()
        tikTok.release()
    }

    companion object {
        const val PROVIDER_PIPER = "piper"
        const val PROVIDER_TIKFINITY = "tikfinity"
        const val PROVIDER_EDGE = "edge"
        const val PROVIDER_TIKTOK = "tiktok"
    }
}
