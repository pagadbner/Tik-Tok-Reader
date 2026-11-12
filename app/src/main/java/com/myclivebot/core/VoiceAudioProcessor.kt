package com.myclivebot.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tanh

enum class VoiceEffectProfile(val id: String, val label: String, val description: String) {
    NATURAL("natural", "Natural", "Sin efectos adicionales"),
    JARVIS("jarvis", "Jarvis", "Filtro cálido y modulación robótica sutil"),
    CORTANA("cortana", "Cortana", "Modulación digital suave y brillo"),
    MEME("meme", "Meme", "Saturación y textura digital exagerada"),
    DJ("dj", "DJ", "Eco corto y modulación electrónica");

    companion object {
        fun fromId(id: String): VoiceEffectProfile =
            values().firstOrNull { it.id == id } ?: NATURAL
    }
}

/** Lightweight PCM effects. Runs on the audio callback thread with bounded memory. */
class VoiceAudioProcessor(profile: VoiceEffectProfile, sampleRate: Int) {
    private val profile = profile
    private val rate = sampleRate.coerceIn(8_000, 48_000)
    private val delay = ShortArray((rate * 0.13f).toInt().coerceAtLeast(1))
    private var delayIndex = 0
    private var phase = 0.0
    private var lowPass = 0f
    private var heldSample = 0f
    private var holdCounter = 0

    fun process(input: FloatArray): ShortArray {
        val output = ShortArray(input.size)
        val hz = when (profile) {
            VoiceEffectProfile.JARVIS -> 34.0
            VoiceEffectProfile.CORTANA -> 5.0
            VoiceEffectProfile.MEME -> 48.0
            VoiceEffectProfile.DJ -> 8.0
            VoiceEffectProfile.NATURAL -> 0.0
        }
        val step = 2.0 * PI * hz / rate
        for (i in input.indices) {
            var sample = input[i].coerceIn(-1f, 1f)
            when (profile) {
                VoiceEffectProfile.NATURAL -> Unit
                VoiceEffectProfile.JARVIS -> {
                    lowPass += 0.22f * (sample - lowPass)
                    sample = lowPass * (0.88f + 0.12f * sin(phase).toFloat())
                }
                VoiceEffectProfile.CORTANA -> {
                    sample *= 0.94f + 0.06f * sin(phase).toFloat()
                    lowPass += 0.48f * (sample - lowPass)
                    sample = sample * 0.78f + lowPass * 0.22f
                }
                VoiceEffectProfile.MEME -> {
                    if (holdCounter-- <= 0) {
                        heldSample = sample
                        holdCounter = 2
                    }
                    val clipped = (heldSample * 1.8f).coerceIn(-1f, 1f)
                    sample = (tanh(clipped * 2.2f) / tanh(2.2f)).toFloat()
                }
                VoiceEffectProfile.DJ -> {
                    val delayed = delay[delayIndex].toFloat() / Short.MAX_VALUE
                    sample = (sample * (0.90f + 0.10f * sin(phase).toFloat()) + delayed * 0.28f)
                        .coerceIn(-1f, 1f)
                    delay[delayIndex] = (sample * Short.MAX_VALUE).toInt().toShort()
                    delayIndex = (delayIndex + 1) % delay.size
                }
            }
            phase += step
            if (phase >= 2.0 * PI) phase -= 2.0 * PI
            output[i] = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return output
    }
}
