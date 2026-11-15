package com.myclivebot.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.ihorshevchuk.piper.engine.PiperCreateOptions
import dev.ihorshevchuk.piper.engine.PiperEngine
import dev.ihorshevchuk.piper.engine.PiperSynthesizeOptions
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject

/** On-device Piper neural TTS with selectable voice, volume and speaking speed. */
class PiperSpeechEngine(
    context: Context,
    private val voiceId: String = PiperVoiceCatalog.DEFAULT_VOICE_ID,
    volume: Float = 1.0f,
    speed: Float = 1.0f
) : SpeechEngine {
    private val appContext = context.applicationContext
    @Volatile private var engine: PiperEngine? = null
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var volume: Float = volume.coerceIn(0f, 1f)
    @Volatile private var speed: Float = speed.coerceIn(0.65f, 1.5f)
    @Volatile private var muted: Boolean = false
    // Voice metadata is immutable for this engine instance; avoid reopening and parsing
    // the JSON config for every short chat utterance.
    private val audioSampleRate: Int by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        JSONObject(PiperVoiceCatalog.configFile(appContext, voiceId).readText())
            .optJSONObject("audio")?.optInt("sample_rate", 22_050) ?: 22_050
    }
    private val minAudioBufferSize: Int by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AudioTrack.getMinBufferSize(
            audioSampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
    }

    companion object {
        const val VOICE_ID = "es_MX-ald-medium"
        private const val ALD_SHA256 = "019b3803293c93e34a206dd2e53a3889209a514e786fd7144f7b70196c579b63"

        fun modelFile(context: Context) = PiperVoiceCatalog.modelFile(context, VOICE_ID)
        fun isVoiceInstalled(context: Context): Boolean = isVoiceInstalled(context, VOICE_ID)

        private fun isSupportedLocale(code: String?): Boolean {
            val normalized = code?.trim()?.replace('-', '_')?.lowercase() ?: return false
            return normalized.matches(Regex("^[a-z]{2,3}([_-][a-z0-9]{2,8})*$"))
        }

        fun isVoiceInstalled(context: Context, id: String): Boolean {
            val model = PiperVoiceCatalog.modelFile(context, id)
            val config = PiperVoiceCatalog.configFile(context, id)
            if (!model.isFile || model.length() < 20_000_000L || !config.isFile || config.length() < 100L) return false
            return runCatching {
                val json = JSONObject(config.readText())
                isSupportedLocale(json.optJSONObject("language")?.optString("code")) &&
                    json.optJSONObject("audio")?.optInt("sample_rate", 0)?.let { it in 8_000..48_000 } == true &&
                    json.optJSONObject("phoneme_id_map") != null
            }.getOrDefault(false)
        }

        suspend fun downloadVoice(context: Context) = downloadVoice(context, VOICE_ID)

        suspend fun downloadVoice(
            context: Context,
            id: String,
            onProgress: (Int, String) -> Unit = { _, _ -> }
        ) = withContext(Dispatchers.IO) {
            val voice = PiperVoiceCatalog.find(id)
            val model = PiperVoiceCatalog.modelFile(context, voice.id)
            val config = PiperVoiceCatalog.configFile(context, voice.id)
            model.parentFile?.mkdirs()
            if (!isVoiceInstalled(context, voice.id)) {
                if (voice.directDownload) {
                    downloadFile(
                        voice.modelUrl ?: error("Falta URL del modelo"),
                        model,
                        voice.expectedSha256 ?: if (voice.id == VOICE_ID) ALD_SHA256 else null
                    ) { percent -> onProgress(percent, "Descargando modelo") }
                    downloadFile(voice.configUrl ?: error("Falta URL de configuración"), config) { percent ->
                        onProgress(percent, "Descargando configuración")
                    }
                } else {
                    val archive = File(model.parentFile, "${voice.id}.tar.gz.part")
                    try {
                        downloadFile(voice.archiveUrl ?: error("Falta URL del paquete"), archive) { percent ->
                            onProgress(percent, "Descargando paquete de voz")
                        }
                        onProgress(100, "Instalando modelo")
                        extractVoiceArchive(archive, model, config)
                    } finally {
                        archive.delete()
                    }
                }
            }
            check(isVoiceInstalled(context, voice.id)) {
                "La voz ${voice.name} no superó la validación. El modelo o su configuración están incompletos."
            }
            onProgress(100, "Voz instalada")
        }

        suspend fun deleteVoice(context: Context, id: String) = withContext(Dispatchers.IO) {
            val voice = PiperVoiceCatalog.find(id)
            val model = PiperVoiceCatalog.modelFile(context, voice.id)
            val config = PiperVoiceCatalog.configFile(context, voice.id)
            val temporaryFiles = listOf(
                File(model.parentFile, model.name + ".part"),
                File(config.parentFile, config.name + ".part"),
                File(model.parentFile, "${voice.id}.tar.gz.part")
            )
            (listOf(model, config) + temporaryFiles).forEach { file ->
                if (file.exists() && !file.delete()) {
                    throw IOException("No se pudo eliminar ${file.name}.")
                }
            }
            check(!model.exists() && !config.exists()) { "La voz no se eliminó completamente." }
        }

        private fun downloadFile(
            url: String,
            target: File,
            expectedSha256: String? = null,
            onProgress: (Int) -> Unit = {}
        ) {
            val temp = File(target.parentFile, target.name + ".part")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 25_000
                readTimeout = 180_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TikLBot-Android")
            }
            try {
                connection.connect()
                check(connection.responseCode in 200..299) { "Descarga HTTP ${connection.responseCode}" }
                val totalBytes = connection.contentLengthLong
                var downloadedBytes = 0L
                onProgress(0)
                connection.inputStream.use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloadedBytes += count
                            if (totalBytes > 0L) {
                                val percent = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
                                onProgress(percent)
                            }
                        }
                    }
                }
                check(temp.length() > 100L) { "Archivo de voz vacío o inválido" }
                onProgress(100)
                if (expectedSha256 != null) check(sha256(temp) == expectedSha256) {
                    "El modelo descargado no coincide con su huella SHA-256."
                }
                if (target.exists()) target.delete()
                check(temp.renameTo(target)) { "No se pudo guardar ${target.name}" }
            } finally {
                connection.disconnect()
                temp.delete()
            }
        }

        private fun extractVoiceArchive(archive: File, model: File, config: File) {
            val modelTemp = File(model.parentFile, model.name + ".part")
            val configTemp = File(config.parentFile, config.name + ".part")
            var foundModel = false
            var foundConfig = false
            try {
                TarArchiveInputStream(
                    GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive)))
                ).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (!entry.isFile || entry.size <= 0L) continue
                        val name = entry.name.substringAfterLast('/')
                        when {
                            name.endsWith(".onnx", ignoreCase = true) && !name.endsWith(".encoder.onnx") &&
                                !name.endsWith(".decoder.onnx") -> {
                                modelTemp.outputStream().buffered().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    var remaining = entry.size
                                    while (remaining > 0) {
                                        val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                        if (count < 0) throw IOException("Modelo ONNX truncado dentro del paquete.")
                                        output.write(buffer, 0, count)
                                        remaining -= count
                                    }
                                }
                                foundModel = true
                            }
                            name.endsWith(".onnx.json", ignoreCase = true) -> {
                                configTemp.outputStream().buffered().use { output ->
                                    val buffer = ByteArray(16 * 1024)
                                    var remaining = entry.size
                                    while (remaining > 0) {
                                        val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                        if (count < 0) throw IOException("Configuración JSON truncada dentro del paquete.")
                                        output.write(buffer, 0, count)
                                        remaining -= count
                                    }
                                }
                                foundConfig = true
                            }
                        }
                    }
                }
                check(foundModel && foundConfig) { "El paquete comunitario no contiene el modelo ONNX y su JSON." }
                check(modelTemp.length() > 20_000_000L) { "El modelo extraído está incompleto." }
                val configJson = JSONObject(configTemp.readText())
                val localeCode = configJson.optJSONObject("language")?.optString("code")
                check(isSupportedLocale(localeCode)) {
                    "El paquete no contiene un código de idioma Piper válido."
                }
                val sampleRate = configJson.optJSONObject("audio")?.optInt("sample_rate", 0) ?: 0
                check(sampleRate in 8_000..48_000) {
                    "El modelo no contiene una configuración de audio válida."
                }
                if (model.exists()) model.delete()
                if (config.exists()) config.delete()
                check(modelTemp.renameTo(model) && configTemp.renameTo(config)) {
                    "No se pudo instalar el paquete de voz."
                }
            } finally {
                modelTemp.delete()
                configTemp.delete()
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
        check(isVoiceInstalled(appContext, voiceId)) {
            "Primero descarga la voz seleccionada desde el catálogo."
        }
        copyAssetTreeIfNeeded("espeak-ng-data", File(appContext.filesDir, "espeak-ng-data"))
        val model = PiperVoiceCatalog.modelFile(appContext, voiceId)
        val options = PiperCreateOptions(
            modelPath = model.absolutePath,
            configPath = model.absolutePath + ".json",
            espeakDataPath = File(appContext.filesDir, "espeak-ng-data").absolutePath
        )
        engine = PiperEngine(options, appFilesDir = appContext.filesDir)
            .also { it.memoryThresholdBytes = 450L * 1024L * 1024L }
    }

    override suspend fun speak(text: String) = withContext(Dispatchers.IO) {
        initialize()
        val piper = engine ?: error("Piper no está inicializado")
        val sampleRate = audioSampleRate
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
            .setBufferSizeInBytes(minAudioBufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val totalFrames = AtomicLong(0L)
        currentTrack = track
        try {
            track.setVolume(if (muted) 0f else volume.coerceIn(0f, 1f))
            track.play()
            val defaultOptions = piper.defaultSynthesizeOptions()
            val synthOptions = defaultOptions.copy(
                lengthScale = (1f / speed.coerceIn(0.65f, 1.5f)).coerceIn(0.67f, 1.54f)
            )
            piper.synthesize(text.take(500), synthOptions = synthOptions, onSamples = { samples ->
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
            val expectedFrames = totalFrames.get()
            val deadline = System.currentTimeMillis() + 45_000L
            while (track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                track.playbackHeadPosition.toLong() < expectedFrames &&
                System.currentTimeMillis() < deadline
            ) Thread.sleep(15)
        } finally {
            runCatching { track.stop() }
            track.release()
            currentTrack = null
        }
    }

    fun updatePlaybackSettings(nextVolume: Float, nextSpeed: Float) {
        volume = nextVolume.coerceIn(0f, 1f)
        speed = nextSpeed.coerceIn(0.65f, 1.5f)
        currentTrack?.let { track -> runCatching { track.setVolume(if (muted) 0f else volume) } }
    }

    fun setMuted(value: Boolean) {
        muted = value
        currentTrack?.let { track -> runCatching { track.setVolume(if (value) 0f else volume.coerceIn(0f, 1f)) } }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        currentTrack?.let { runCatching { it.pause(); it.flush(); it.stop() } }
        engine?.cancel()
        Unit
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
            appContext.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            target.mkdirs()
            children.forEach { child -> copyAssetTreeIfNeeded("$assetPath/$child", File(target, child)) }
        }
        if (assetPath == "espeak-ng-data") marker.writeText("ok")
    }
}
