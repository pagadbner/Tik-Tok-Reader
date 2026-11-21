package com.myclivebot.core

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/**
 * Keeps one stable speech facade for the existing FIFO queue. Changing provider
 * updates the active engine without reconnecting or replacing the TikTok LIVE socket.
 */
class SelectableSpeechEngine(
    initial: ReaderSettings,
    private val onApiError: (Throwable) -> Unit = {}
) : SpeechEngine {
    private val settings = AtomicReference(initial)
    private val edge = EdgeTtsSpeechEngine(initial.edgeVoiceId, initial.volume, initial.speed)
    private val tikTok = TikTokTtsSpeechEngine(initial.tikTokVoiceId, initial.volume, initial.speed)
    private val tikFinity = TikFinitySpeechEngine(
        initial.apiVoiceId,
        initial.tikFinityChannelId,
        initial.volume,
        initial.speed
    )

    fun update(next: ReaderSettings) {
        settings.set(next)
        edge.update(next.edgeVoiceId, next.volume, next.speed)
        tikTok.update(next.tikTokVoiceId, next.volume, next.speed)
        tikFinity.update(next.apiVoiceId, next.tikFinityChannelId, next.volume, next.speed)
    }

    fun setMuted(value: Boolean) {
        edge.setMuted(value)
        tikTok.setMuted(value)
        tikFinity.setMuted(value)
    }

    override suspend fun initialize() {
        when (supportedProvider(settings.get().speechProvider)) {
            PROVIDER_TIKFINITY -> tikFinity.initialize()
            PROVIDER_TIKTOK -> tikTok.initialize()
            else -> edge.initialize()
        }
    }

    override suspend fun speak(text: String) {
        val current = settings.get()
        try {
            when (supportedProvider(current.speechProvider)) {
                PROVIDER_TIKFINITY -> {
                    val voice = if (current.apiRandomVoice) TikFinityVoiceCatalog.randomVoice() else current.apiVoiceId
                    tikFinity.update(voice, current.tikFinityChannelId, current.volume, current.speed)
                    tikFinity.speak(text)
                }
                PROVIDER_TIKTOK -> {
                    tikTok.update(current.tikTokVoiceId, current.volume, current.speed)
                    tikTok.speak(text)
                }
                else -> {
                    edge.update(current.edgeVoiceId, current.volume, current.speed)
                    edge.speak(text)
                }
            }
        } catch (error: Exception) {
            if (error !is CancellationException) onApiError(error)
            throw error
        }
    }

    override suspend fun stop() {
        runCatching { edge.stop() }
        runCatching { tikTok.stop() }
        runCatching { tikFinity.stop() }
    }

    fun release() {
        edge.release()
        tikTok.release()
        tikFinity.release()
    }

    private fun supportedProvider(value: String): String = when (value) {
        PROVIDER_TIKFINITY, PROVIDER_TIKTOK, PROVIDER_EDGE -> value
        else -> PROVIDER_EDGE
    }

    companion object {
        const val PROVIDER_TIKFINITY = "tikfinity"
        const val PROVIDER_EDGE = "edge"
        const val PROVIDER_TIKTOK = "tiktok"
    }
}
