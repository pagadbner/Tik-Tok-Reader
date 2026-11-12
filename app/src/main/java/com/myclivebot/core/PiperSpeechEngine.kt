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
    private val volume: Float = 1.0f,
    private val speed: Float = 1.0f
) : SpeechEngine {
    private val appContext = context.applicationContext
    @Volatile private var engine: PiperEngine? = null
    @Volatile private var currentTrack: AudioTrack? = null

    companion object {
        const val VOICE_ID = "es_MX-ald-medium"
        private const val ALD_SHA256 = "019b3803293c93e34a206dd2e53a3889209a514e786fd7144f7b70196c579b63"

        fun modelFile(context: Context) = PiperVoiceCatalog.modelFile(context, VOICE_ID)
        fun isVoiceInstalled(context: Context): Boolean = isVoiceInstalled(context, VOICE_ID)

        private fun isSpanishLocale(code: String?): Boolean {
            val normalized = code?.trim()?.replace('-', '_')?.lowercase() ?: return false
            return normalized == "es" || normalized.startsWith("es_")
        }

        fun isVoiceInstalled(context: Context, id: String): Boolean {
            val model = PiperVoiceCatalog.modelFile(context, id)
            val config = PiperVoiceCatalog.configFile(context, id)
            if (!model.isFile || model.length() < 20_000_000L || !config.isFile || config.length() < 100L) return false
            return runCatching {
                val json = JSONObject(config.readText())
                isSpanishLocale(json.optJSONObject("language")?.optString("code")) &&
                    json.optJSONObject("audio")?.optInt("sample_rate", 0)?.let { it in 8_000..48_000 } == true &&
                    json.optJSONObject("phoneme_id_map") != null
            }.getOrDefault(false)
        }

        suspend fun downloadVoice(context: Context) = downloadVoice(context, VOICE_ID)

        suspend fun downloadVoice(context: Context, id: String) = withContext(Dispatchers.IO) {
            val voice = PiperVoiceCatalog.find(id)
            val model = PiperVoiceCatalog.modelFile(context, voice.id)
            val config = PiperVoiceCatalog.configFile(context, voice.id)
            model.parentFile?.mkdirs()
            if (!isVoiceInstalled(context, voice.id)) {
                if (voice.official) {
                    downloadFile(voice.modelUrl ?: error("Falta URL del modelo"), model,
                        if (voice.id == VOICE_ID) ALD_SHA256 else null)
                    downloadFile(voice.configUrl ?: error("Falta URL de configuración"), config)
                } else {
                    val archive = File(model.parentFile, "${voice.id}.tar.gz.part")
                    try {
                        downloadFile(voice.archiveUrl ?: error("Falta URL del paquete"), archive)
                        extractVoiceArchive(archive, model, config)
                    } finally {
                        archive.delete()
                    }
                }
            }
            check(isVoiceInstalled(context, voice.id)) {
                "La voz ${voice.name} no superó la validación. El modelo o su configuración están incompletos."
            }
        }

        private fun downloadFile(url: String, target: File, expectedSha256: String? = null) {
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
                connection.inputStream.use { input ->
                    temp.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                check(temp.length() > 100L) { "Archivo de voz vacío o inválido" }
                if (expectedSha256 != null) check(sha256(temp) == expectedSha256) {
                    "El modelo oficial no coincide con su huella SHA-256."
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
                check(isSpanishLocale(localeCode)) {
                    "El paquete no corresponde a una voz en español (es, es-ES o es-MX)."
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
        val config = JSONObject(PiperVoiceCatalog.configFile(appContext, voiceId).readText())
        val sampleRate = config.optJSONObject("audio")?.optInt("sample_rate", 22_050) ?: 22_050
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
            track.setVolume(volume.coerceIn(0f, 1f))
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
