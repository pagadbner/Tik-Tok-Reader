package com.myclivebot.core

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Windows/JVM MP3 output. Decodes frames to PCM and sends them to the selected
 * system output device; it never touches Android AudioTrack.
 */
object DesktopAudioPlayer {
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var activeLine: SourceDataLine? = null

    fun play(mp3: ByteArray, volume: Float, speed: Float, muted: Boolean) {
        require(mp3.isNotEmpty()) { "No se recibió audio para reproducir." }
        stopRequested.set(false)
        var line: SourceDataLine? = null
        val gain = if (muted) 0f else volume.coerceIn(0f, 1f)
        val playbackRate = speed.coerceIn(0.65f, 1.5f)
        val bitstream = Bitstream(ByteArrayInputStream(mp3))
        val decoder = Decoder()
        try {
            while (!stopRequested.get()) {
                val frame = bitstream.readFrame() ?: break
                try {
                    val decoded = decoder.decodeFrame(frame, bitstream) as SampleBuffer
                    val samples = decoded.buffer
                    val sampleCount = decoded.bufferLength
                    if (line == null) {
                        val format = AudioFormat(
                            decoded.sampleFrequency.toFloat() * playbackRate,
                            16,
                            decoded.channelCount,
                            true,
                            false
                        )
                        line = AudioSystem.getSourceDataLine(format).apply {
                            open(format)
                            start()
                        }
                        activeLine = line
                    }
                    val pcm = ByteArray(sampleCount * 2)
                    for (index in 0 until sampleCount) {
                        val sample = (samples[index] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        pcm[index * 2] = (sample and 0xff).toByte()
                        pcm[index * 2 + 1] = ((sample shr 8) and 0xff).toByte()
                    }
                    var offset = 0
                    while (offset < pcm.size && !stopRequested.get()) {
                        val written = line.write(pcm, offset, pcm.size - offset)
                        if (written <= 0) break
                        offset += written
                    }
                } finally {
                    bitstream.closeFrame()
                }
            }
            if (!stopRequested.get()) line?.drain()
        } finally {
            activeLine = null
            runCatching { line?.stop() }
            runCatching { line?.close() }
            runCatching { bitstream.close() }
        }
    }

    fun playPcm(samples: ShortArray, sampleRate: Int, volume: Float, muted: Boolean) {
        playPcmStream(sampleRate, volume, muted) { emit -> emit(samples) }
    }

    /**
     * Plays Piper callback chunks as they are synthesized instead of buffering the
     * entire utterance. stop() closes the line; subsequent native chunks are ignored.
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
        var line: SourceDataLine? = null
        val gain = volume.coerceIn(0f, 1f)
        try {
            val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            val outputLine = AudioSystem.getSourceDataLine(format).apply { open(format); start() }
            line = outputLine
            activeLine = outputLine
            val emit: (ShortArray) -> Unit = { samples ->
                if (!stopRequested.get() && samples.isNotEmpty()) {
                    val pcm = ByteArray(samples.size * 2)
                    for (index in samples.indices) {
                        val value = (samples[index].toInt() * gain).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        pcm[index * 2] = (value and 0xff).toByte()
                        pcm[index * 2 + 1] = ((value shr 8) and 0xff).toByte()
                    }
                    var offset = 0
                    while (offset < pcm.size && !stopRequested.get()) {
                        try {
                            val written = outputLine.write(pcm, offset, pcm.size - offset)
                            if (written <= 0) break
                            offset += written
                        } catch (error: Exception) {
                            if (!stopRequested.get()) throw error
                            break
                        }
                    }
                }
            }
            synthesize(emit)
            if (!stopRequested.get()) outputLine.drain()
        } finally {
            activeLine = null
            runCatching { line?.stop() }
            runCatching { line?.close() }
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
