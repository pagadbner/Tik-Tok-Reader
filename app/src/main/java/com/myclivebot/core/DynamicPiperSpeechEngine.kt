package com.myclivebot.core

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serial speech facade that can change voice/volume/speed while the LIVE socket stays connected.
 * Random mode selects only models already installed on this device.
 */
class DynamicPiperSpeechEngine(
    context: Context,
    initial: ReaderSettings
) : SpeechEngine {
    private data class Config(
        val voiceId: String,
        val volume: Float,
        val speed: Float,
        val randomVoices: Boolean
    )

    private val appContext = context.applicationContext
    private val config = AtomicReference(toConfig(initial))
    private val engines = ConcurrentHashMap<String, PiperSpeechEngine>()
    private val lock = Mutex()
    @Volatile private var muted: Boolean = false

    fun update(settings: ReaderSettings) {
        val next = toConfig(settings)
        config.set(next)
        engines.forEach { (voiceId, engine) ->
            if (voiceId == next.voiceId) engine.updatePlaybackSettings(next.volume, next.speed)
        }
    }

    fun setMuted(value: Boolean) {
        muted = value
        engines.values.forEach { it.setMuted(value) }
    }

    override suspend fun initialize() {
        lock.withLock {
            engineFor(config.get().voiceId, config.get()).initialize()
        }
    }

    override suspend fun speak(text: String) {
        lock.withLock {
            val current = config.get()
            val voiceId = if (current.randomVoices) {
                val selectedLanguage = PiperVoiceCatalog.find(current.voiceId).language
                val installed = PiperVoiceCatalog.voices.asSequence()
                    .filter { it.language == selectedLanguage }
                    .map { it.id }
                    .filter { PiperSpeechEngine.isVoiceInstalled(appContext, it) }
                    .toList()
                if (installed.isNotEmpty()) installed[Random.nextInt(installed.size)] else current.voiceId
            } else current.voiceId
            engineFor(voiceId, current).speak(text)
        }
    }

    override suspend fun stop() {
        lock.withLock {
            engines.values.forEach { runCatching { it.stop() } }
        }
    }

    fun release() {
        engines.values.forEach { runCatching { it.release() } }
        engines.clear()
    }

    private fun engineFor(voiceId: String, current: Config): PiperSpeechEngine {
        val selected = engines.getOrPut(voiceId) {
            PiperSpeechEngine(appContext, voiceId, current.volume, current.speed)
        }
        selected.updatePlaybackSettings(current.volume, current.speed)
        selected.setMuted(muted)
        // Retain at most two voice models, independent of slider movement.
        if (engines.size > 2) {
            engines.keys.firstOrNull { it != voiceId }?.let { staleKey ->
                engines.remove(staleKey)?.release()
            }
        }
        return selected
    }

    private fun toConfig(settings: ReaderSettings) = Config(
        voiceId = settings.voiceId,
        volume = settings.volume.coerceIn(0f, 1f),
        speed = settings.speed.coerceIn(0.65f, 1.5f),
        randomVoices = settings.randomVoices
    )
}
