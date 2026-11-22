package com.myclivebot.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopAudioPlayerTest {
    @Test
    fun upsamplesPiperRateToStandardWindowsRate() {
        val result = DesktopAudioPlayer.resampleMonoPcm(
            shortArrayOf(0, 1_000, 2_000, 3_000),
            inputRate = 22_050,
            outputRate = 44_100
        )

        assertEquals(7, result.size)
        assertEquals(0.toShort(), result.first())
        assertEquals(3_000.toShort(), result.last())
        assertTrue((1 until result.size).all { result[it] >= result[it - 1] })
    }

    @Test
    fun resamplingKeepsConstantSignalLevel() {
        val input = ShortArray(64) { 4_200 }
        val result = DesktopAudioPlayer.resampleMonoPcm(input, 24_000, 44_100)

        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it == 4_200.toShort() })
    }

    @Test
    fun emptyAudioStaysEmpty() {
        assertTrue(DesktopAudioPlayer.resampleMonoPcm(shortArrayOf(), 24_000, 44_100).isEmpty())
    }

    @Test
    fun rejectsUnsupportedInputSampleRate() {
        assertFailsWith<IllegalArgumentException> {
            DesktopAudioPlayer.resampleMonoPcm(shortArrayOf(1, 2), 1_000, 44_100)
        }
    }
}
