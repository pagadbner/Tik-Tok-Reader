package com.myclivebot.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.ihorshevchuk.piper.engine.PiperCreateOptions
import dev.ihorshevchuk.piper.engine.PiperEngine
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** On-device Piper neural TTS. The Spanish model is downloaded once by the user. */
class PiperSpeechEngine(context: Context) : SpeechEngine {
    private val appContext = context.applicationContext
    @Volatile private var engine: PiperEngine? = null
    @Volatile private var currentTrack: AudioTrack? = null

    companion object {
        const val VOICE_ID = "es_MX-ald-medium"
        private const val MODEL_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_MX/ald/medium/es_MX-ald-medium.onnx?download=true"
        private const val CONFIG_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_MX/ald/medium/es_MX-ald-medium.onnx.json?download=true"
        private const val MODEL_SHA256 = "019b3803293c93e34a206dd2e53a3889209a514e786fd7144f7b70196c579b63"

        fun modelFile(context: Context) = File(context.filesDir, "voices/$VOICE_ID.onnx")
        fun isVoiceInstalled(context: Context): Boolean {
            val model = modelFile(context)
            val config = File(model.absolutePath + ".json")
            return model.isFile && model.length() > 50_000_000L && config.isFile &&
                runCatching { config.readText().contains("\"code\": \"es_MX\"") }.getOrDefault(false)
        }

        suspend fun downloadVoice(context: Context) = withContext(Dispatchers.IO) {
            val model = modelFile(context)
            val config = File(model.absolutePath + ".json")
            model.parentFile?.mkdirs()
            if (!model.exists() || model.length() < 50_000_000L || sha256(model) != MODEL_SHA256) {
                downloadAtomically(MODEL_URL, model, MODEL_SHA256)
            }
            if (!config.exists() || config.length() < 100L || !config.readText().contains("\"code\": \"es_MX\"")) {
                downloadAtomically(CONFIG_URL, config)
            }
            require(isVoiceInstalled(context)) { "La descarga de la voz Piper no se pudo validar." }
        }

        private fun downloadAtomically(url: String, target: File, expectedSha256: String? = null) {
            val temp = File(target.parentFile, target.name + ".part")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TikTokLiveReader-Android")
            }
            try {
                connection.connect()
                check(connection.responseCode in 200..299) { "Descarga HTTP ${connection.responseCode}" }
                connection.inputStream.use { input ->
                    temp.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                check(temp.length() > 100L) { "Archivo de voz vacío o inválido" }
                if (expectedSha256 != null) check(sha256(temp) == expectedSha256) {
                    "La voz descargada no coincide con su huella SHA-256."
                }
                temp.inputStream().use { stream ->
                    val first = stream.read()
                    check(first >= 0 && first != '<'.code) { "El servidor no devolvió un modelo de voz válido" }
                }
                if (target.exists()) target.delete()
                check(temp.renameTo(target)) { "No se pudo guardar ${target.name}" }
            } finally {
                connection.disconnect()
                if (temp.exists()) temp.delete()
            }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext
        check(isVoiceInstalled(appContext)) { "Primero descarga la voz Piper español desde la aplicación." }
        copyAssetTreeIfNeeded("espeak-ng-data", File(appContext.filesDir, "espeak-ng-data"))
        val model = modelFile(appContext)
        check(sha256(model) == MODEL_SHA256) { "El modelo Piper está dañado. Vuelve a descargar la voz." }
        engine = PiperEngine(
            PiperCreateOptions(
                modelPath = model.absolutePath,
                configPath = model.absolutePath + ".json",
                espeakDataPath = File(appContext.filesDir, "espeak-ng-data").absolutePath
            ),
            appFilesDir = appContext.filesDir
        ).also { it.memoryThresholdBytes = 450L * 1024L * 1024L }
    }

    override suspend fun speak(text: String) = withContext(Dispatchers.IO) {
        initialize()
        val piper = engine ?: error("Piper no está inicializado")
        val sampleRate = 22_050
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val totalFrames = AtomicLong(0L)
        currentTrack = track
        try {
            track.play()
            piper.synthesize(text.take(500), onSamples = { samples ->
                if (samples.isNotEmpty()) {
                    totalFrames.addAndGet(samples.size.toLong())
                    val pcm = ShortArray(samples.size) { index ->
                        (samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                    }
                    var offset = 0
                    while (offset < pcm.size) {
                        val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) throw IllegalStateException("AudioTrack no pudo escribir el audio ($written).")
                        offset += written
                    }
                }
            })
            // Wait until every synthesized frame has reached the speaker.
            val expectedFrames = totalFrames.get()
            val deadline = System.currentTimeMillis() + 30_000L
            while (track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                track.playbackHeadPosition.toLong() < expectedFrames &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(15)
            }
        } finally {
            runCatching { track.stop() }
            track.release()
            currentTrack = null
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        currentTrack?.let { runCatching { it.pause(); it.flush(); it.stop() } }
        engine?.cancel()
    }

    fun release() {
        engine?.close()
        engine = null
    }

    private fun copyAssetTreeIfNeeded(assetPath: String, target: File) {
        val marker = File(appContext.filesDir, ".espeak-data-ready")
        if (marker.isFile) return
        if (target.exists()) target.deleteRecursively()
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        } else {
            target.mkdirs()
            children.forEach { child -> copyAssetTreeIfNeeded("$assetPath/$child", File(target, child)) }
        }
        if (assetPath == "espeak-ng-data") marker.writeText("ok")
    }
}
