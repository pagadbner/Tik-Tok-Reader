package com.myclivebot.core

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Windows/JVM audio output.
 *
 * Edge TTS returns 24 kHz mono MP3 and Piper voices can use several sample
 * rates. Windows audio devices do not all accept those source formats directly,
 * so audio is normalized to a common PCM output rate before it reaches Java Sound.
 */
object DesktopAudioPlayer {
    private val outputRates = listOf(44_100, 48_000, 22_050)
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var activeLine: SourceDataLine? = null

    fun play(mp3: ByteArray, volume: Float, speed: Float, muted: Boolean) {
        require(mp3.isNotEmpty()) { "No se recibió audio para reproducir." }
        if (muted || volume <= 0f) return
        stopRequested.set(false)

        val playbackRate = speed.coerceIn(0.65f, 1.5f)
        val decodedPcm = ByteArrayOutputStream()
        var sourceRate = 0

        val bitstream = Bitstream(ByteArrayInputStream(mp3))
        try {
            val decoder = Decoder()
            while (!stopRequested.get()) {
                val frame = bitstream.readFrame() ?: break
                try {
                    val decoded = decoder.decodeFrame(frame, bitstream) as SampleBuffer
                    if (sourceRate == 0) sourceRate = decoded.sampleFrequency
                    val channels = decoded.channelCount.coerceAtLeast(1)
                    val samples = decoded.buffer
                    val frames = decoded.bufferLength / channels
                    // Speech is mono. Downmix stereo/multichannel sources before resampling.
                    for (frameIndex in 0 until frames) {
                        var sum = 0L
                        for (channel in 0 until channels) {
                            sum += samples[frameIndex * channels + channel].toLong()
                        }
                        val mono = (sum / channels).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        decodedPcm.write(mono and 0xff)
                        decodedPcm.write((mono shr 8) and 0xff)
                    }
                } finally {
                    bitstream.closeFrame()
                }
            }
        } finally {
            runCatching { bitstream.close() }
        }

        if (stopRequested.get()) return
        val raw = decodedPcm.toByteArray()
        if (raw.isEmpty() || sourceRate <= 0) throw IOException("El decodificador MP3 no produjo muestras de audio.")
        val input = ShortArray(raw.size / 2) { index ->
            (((raw[index * 2].toInt() and 0xff) or
                ((raw[index * 2 + 1].toInt() and 0xff) shl 8))).toShort()
        }
        val output = openOutputLine()
        activeLine = output.line
        try {
            val adjustedSourceRate = (sourceRate * playbackRate).toInt().coerceIn(8_000, 96_000)
            val pcm = resampleMonoPcm(input, adjustedSourceRate, output.sampleRate)
            writeSamples(output.line, pcm, volume.coerceIn(0f, 1f), output.channels)
            if (!stopRequested.get()) output.line.drain()
        } finally {
            if (activeLine === output.line) activeLine = null
            runCatching { output.line.stop() }
            runCatching { output.line.close() }
        }
    }

    fun playPcm(samples: ShortArray, sampleRate: Int, volume: Float, muted: Boolean) {
        playPcmStream(sampleRate, volume, muted) { emit -> emit(samples) }
    }

    /**
     * Streams Piper chunks through a stateful resampler. The resampler retains
     * the boundary sample between chunks so conversion does not create gaps.
     */
    fun playPcmStream(
        sampleRate: Int,
        volume: Float,
        muted: Boolean,
        synthesize: ((ShortArray) -> Unit) -> Unit
    ) {
        if (muted || volume <= 0f) return
        require(sampleRate in 8_000..96_000) { "Frecuencia de audio Piper no válida." }
        stopRequested.set(false)
        val output = openOutputLine()
        activeLine = output.line
        val resampler = StreamingMonoResampler(sampleRate, output.sampleRate)
        val gain = volume.coerceIn(0f, 1f)
        try {
            val emit: (ShortArray) -> Unit = { samples ->
                if (!stopRequested.get() && samples.isNotEmpty()) {
                    writeSamples(output.line, resampler.process(samples), gain, output.channels)
                }
            }
            synthesize(emit)
            if (!stopRequested.get()) {
                writeSamples(output.line, resampler.finish(), gain, output.channels)
                output.line.drain()
            }
        } finally {
            if (activeLine === output.line) activeLine = null
            runCatching { output.line.stop() }
            runCatching { output.line.close() }
        }
    }

    private data class OpenOutput(val line: SourceDataLine, val sampleRate: Int, val channels: Int)

    private fun openOutputLine(): OpenOutput {
        var lastError: Exception? = null
        for (sampleRate in outputRates) {
            for (channels in listOf(1, 2)) {
                val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
                var line: SourceDataLine? = null
                try {
                    line = AudioSystem.getSourceDataLine(format)
                    line.open(format)
                    line.start()
                    return OpenOutput(line, sampleRate, channels)
                } catch (error: Exception) {
                    runCatching { line?.close() }
                    lastError = error
                }
            }
        }
        throw IOException(
            "Windows no ofrece una salida PCM mono/estéreo compatible (44.1, 48 o 22.05 kHz). Comprueba el dispositivo de salida y su controlador.",
            lastError
        )
    }

    private fun writeSamples(line: SourceDataLine, samples: ShortArray, volume: Float, channels: Int) {
        if (samples.isEmpty() || stopRequested.get()) return
        val bytes = ByteArray(samples.size * 2 * channels)
        var cursor = 0
        for (sample in samples) {
            val value = (sample.toInt() * volume)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            repeat(channels) {
                bytes[cursor++] = (value and 0xff).toByte()
                bytes[cursor++] = ((value shr 8) and 0xff).toByte()
            }
        }
        var offset = 0
        while (offset < bytes.size && !stopRequested.get()) {
            try {
                val written = line.write(bytes, offset, bytes.size - offset)
                if (written <= 0) break
                offset += written
            } catch (error: Exception) {
                if (!stopRequested.get()) throw error
                break
            }
        }
    }

    /** Pure helper used by tests and by the MP3 playback path. */
    internal fun resampleMonoPcm(samples: ShortArray, inputRate: Int, outputRate: Int): ShortArray {
        require(inputRate in 8_000..96_000) { "Frecuencia de entrada no válida." }
        require(outputRate in 8_000..96_000) { "Frecuencia de salida no válida." }
        if (samples.isEmpty()) return shortArrayOf()
        val resampler = StreamingMonoResampler(inputRate, outputRate)
        return resampler.process(samples) + resampler.finish()
    }

    private class StreamingMonoResampler(inputRate: Int, outputRate: Int) {
        private val step = inputRate.toDouble() / outputRate.toDouble()
        private var carry = shortArrayOf()
        private var position = 0.0

        fun process(samples: ShortArray): ShortArray {
            if (samples.isEmpty()) return shortArrayOf()
            val combined = ShortArray(carry.size + samples.size)
            carry.copyInto(combined)
            samples.copyInto(combined, carry.size)

            val output = ArrayList<Short>()
            var cursor = position
            while (cursor + 1.0 < combined.size) {
                val leftIndex = cursor.toInt()
                val fraction = cursor - leftIndex
                val left = combined[leftIndex].toDouble()
                val right = combined[leftIndex + 1].toDouble()
                val interpolated = (left + (right - left) * fraction)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.add(interpolated.toShort())
                cursor += step
            }

            val consumed = cursor.toInt().coerceIn(0, combined.size)
            carry = combined.copyOfRange(consumed, combined.size)
            position = cursor - consumed
            return output.toShortArray()
        }

        fun finish(): ShortArray {
            val last = carry.lastOrNull() ?: return shortArrayOf()
            carry = shortArrayOf()
            return shortArrayOf(last)
        }
    }

    fun stop() {
        stopRequested.set(true)
        activeLine?.let { line ->
            runCatching { line.flush() }
            runCatching { line.stop() }
            runCatching { line.close() }
        }
    }
}
