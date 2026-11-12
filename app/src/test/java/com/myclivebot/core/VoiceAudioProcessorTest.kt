package com.myclivebot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioProcessorTest {
    @Test
    fun naturalProfilePreservesPcmSamplesWithinQuantization() {
        val input = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)
        val output = VoiceAudioProcessor(VoiceEffectProfile.NATURAL, 22_050).process(input)
        assertEquals(input.size, output.size)
        // Float PCM is scaled by Short.MAX_VALUE; -1.0 therefore maps to -32767,
        // not Short.MIN_VALUE (-32768). Both are valid signed 16-bit PCM values.
        assertEquals(-Short.MAX_VALUE, output[0].toInt())
        assertEquals((-0.5f * Short.MAX_VALUE).toInt(), output[1].toInt())
        assertEquals(0, output[2].toInt())
        assertEquals((0.5f * Short.MAX_VALUE).toInt(), output[3].toInt())
        assertEquals(Short.MAX_VALUE.toInt(), output[4].toInt())
    }

    @Test
    fun everySpecialProfileProducesBoundedPcm() {
        val input = FloatArray(2_048) { index ->
            kotlin.math.sin(index * 0.07).toFloat()
        }
        VoiceEffectProfile.values().filter { it != VoiceEffectProfile.NATURAL }.forEach { profile ->
            val output = VoiceAudioProcessor(profile, 22_050).process(input)
            assertEquals(input.size, output.size)
            assertTrue("Out-of-range PCM for ${profile.id}", output.all { it.toInt() in Short.MIN_VALUE..Short.MAX_VALUE })
        }
    }

    @Test
    fun unknownProfileFallsBackToNatural() {
        assertEquals(VoiceEffectProfile.NATURAL, VoiceEffectProfile.fromId("unknown"))
    }
}
