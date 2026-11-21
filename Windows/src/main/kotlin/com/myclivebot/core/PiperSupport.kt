package com.myclivebot.core

import io.github.jvoiceproject.piperjni.PiperJNI
import io.github.jvoiceproject.piperjni.PiperVoice as NativePiperVoice
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject

data class PiperVoiceOption(
    val id: String,
    val name: String,
    val description: String,
    val official: Boolean,
    val modelUrl: String? = null,
    val configUrl: String? = null,
    val archiveUrl: String? = null,
    val directDownload: Boolean = official,
    val expectedSha256: String? = null,
    val language: String = "es",
    val testPhrase: String = "Prueba de voz en español de México. El lector está preparado.",
    val licenseNote: String = if (official) "Modelo del catálogo Piper; consulta su MODEL_CARD."
        else "Modelo comunitario; revisa su licencia antes de redistribuirlo o usarlo comercialmente."
)

object PiperVoiceCatalog {
    val voices = listOf(
        PiperVoiceOption("es_MX-claude-high", "Claude · Alta calidad", "Voz mexicana oficial Piper · calidad high", true,
            modelUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_MX/claude/high/es_MX-claude-high.onnx?download=true",
            configUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_MX/claude/high/es_MX-claude-high.onnx.json?download=true"),
        PiperVoiceOption("es_MX-cortana-19669-epoch-high", "Cortana · Halo (México)", "Piper ONNX · español latino · variante pública de HirCoir", false,
            directDownload = true,
            modelUrl = "https://huggingface.co/spaces/HirCoir/Piper-TTS-Spanish/resolve/main/es_MX-cortana-19669-epoch-high.onnx",
            configUrl = "https://huggingface.co/spaces/HirCoir/Piper-TTS-Spanish/resolve/main/es_MX-cortana-19669-epoch-high.onnx.json",
            expectedSha256 = "f4871e05fdb55e56f85877c9c9ef1279faa879e2dfaa707e7ebcb81d2f6c2f8b",
            licenseNote = "Modelo comunitario publicado por HirCoir; revisa sus condiciones antes de redistribuirlo."),
        PiperVoiceOption("en_us-jarvis-ucm-high", "Jarvis · MCU (English)", "Piper ONNX · inglés estadounidense", false,
            directDownload = true,
            modelUrl = "https://huggingface.co/spaces/HirCoir/Piper-TTS-Spanish/resolve/main/en_us-jarvis_ucm-high.onnx",
            configUrl = "https://huggingface.co/spaces/HirCoir/Piper-TTS-Spanish/resolve/main/en_us-jarvis_ucm-high.onnx.json",
            expectedSha256 = "cfda5b06b59bfcf25d74b1dbabbb4e14fcc6451d53fa2f4fbbefd398f787b5d8",
            language = "en",
            testPhrase = "Hello. I am Jarvis. The live reader is ready.",
            licenseNote = "Modelo comunitario publicado por HirCoir; licencia de redistribución no indicada."),
        PiperVoiceOption("es_MX-ald-medium", "Ald · Media", "Voz mexicana oficial Piper · calidad medium", true,
            modelUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_MX/ald/medium/es_MX-ald-medium.onnx?download=true",
            configUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_MX/ald/medium/es_MX-ald-medium.onnx.json?download=true"),
        PiperVoiceOption("es_ES-davefx-medium", "DaveFX · Español de España", "Voz oficial Piper · calidad medium", true,
            modelUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/davefx/medium/es_ES-davefx-medium.onnx?download=true",
            configUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/davefx/medium/es_ES-davefx-medium.onnx.json?download=true"),
        PiperVoiceOption("es_ES-sharvard-medium", "Sharvard · Español de España", "Voz oficial Piper · calidad medium", true,
            modelUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/sharvard/medium/es_ES-sharvard-medium.onnx?download=true",
            configUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/sharvard/medium/es_ES-sharvard-medium.onnx.json?download=true"),
        PiperVoiceOption("es_AR-daniela-high", "Daniela · Español argentino", "Voz oficial Piper · calidad high", true,
            modelUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_AR/daniela/high/es_AR-daniela-high.onnx?download=true",
            configUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_AR/daniela/high/es_AR-daniela-high.onnx.json?download=true"),
        PiperVoiceOption("es_MX-alan-medium", "Alan · Comunidad", "Voz comunitaria en español · experimental", false,
            archiveUrl = "https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/es/es_MX-alan-medium.tar.gz?download=true"),
        PiperVoiceOption("es_MX-alicia-medium", "Alicia · Comunidad", "Voz comunitaria en español · experimental", false,
            archiveUrl = "https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/es/es_MX-alicia-medium.tar.gz?download=true"),
        PiperVoiceOption("es_MX-carla-medium", "Carla · Comunidad", "Voz comunitaria en español · experimental", false,
            archiveUrl = "https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/es/es_MX-carla-medium.tar.gz?download=true"),
        PiperVoiceOption("es_MX-gabriela-medium", "Gabriela · Comunidad", "Voz comunitaria en español · experimental", false,
            archiveUrl = "https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/es/es_MX-gabriela-medium.tar.gz?download=true"),
        PiperVoiceOption("es_MX-mario-medium", "Mario · Comunidad", "Voz comunitaria en español · experimental", false,
            archiveUrl = "https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/es/es_MX-mario-medium.tar.gz?download=true"),
        PiperVoiceOption("es_MX-mireya-medium", "Mireya · Comunidad", "Voz comunitaria en español · experimental", false,
            archiveUrl = "https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/es/es_MX-mireya-medium.tar.gz?download=true")
    )

    const val DEFAULT_VOICE_ID = "es_MX-claude-high"
    private val appDataDirectory: Path by lazy {
        val local = System.getenv("LOCALAPPDATA")
        if (!local.isNullOrBlank()) Path.of(local, "TikLBot")
        else Path.of(System.getProperty("user.home"), ".tiklbot")
    }
    val voiceDirectory: Path get() = appDataDirectory.resolve("voices")
    fun find(id: String): PiperVoiceOption = voices.firstOrNull { it.id == id }
        ?: voices.first { it.id == DEFAULT_VOICE_ID }
    fun modelFile(id: String): Path = voiceDirectory.resolve("${find(id).id}.onnx")
    fun configFile(id: String): Path = voiceDirectory.resolve("${find(id).id}.onnx.json")
}

/** Loads the selected model once and streams generated PCM to Java Sound. */
class PiperSpeechEngine(
    private val voiceId: String = PiperVoiceCatalog.DEFAULT_VOICE_ID,
    volume: Float = 1f,
    speed: Float = 1f
) : SpeechEngine {
    private val lock = Mutex()
    @Volatile private var nativeVoice: NativePiperVoice? = null
    @Volatile private var piperInstance: PiperJNI? = null
    @Volatile private var selectedVolume = volume.coerceIn(0f, 1f)
    @Volatile private var selectedSpeed = speed.coerceIn(0.65f, 1.5f)
    @Volatile private var muted = false
    private val generation = AtomicLong(0L)

    fun updatePlaybackSettings(volume: Float, speed: Float) {
        selectedVolume = volume.coerceIn(0f, 1f)
        selectedSpeed = speed.coerceIn(0.65f, 1.5f)
    }

    fun setMuted(value: Boolean) { muted = value }

    override suspend fun initialize() {
        lock.withLock { withContext(Dispatchers.IO) { ensureVoiceLoaded() } }
    }

    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        lock.withLock {
            withContext(Dispatchers.IO) {
                val ticket = generation.get()
                val voice = ensureVoiceLoaded()
                val piper = piperInstance ?: throw IllegalStateException("El motor Piper no está inicializado.")
                val adjustedSampleRate = (voice.sampleRate * selectedSpeed.coerceIn(0.65f, 1.5f)).toInt()
                if (ticket == generation.get()) {
                    DesktopAudioPlayer.playPcmStream(adjustedSampleRate, selectedVolume, muted) { emit ->
                        piper.textToAudio(voice, text.take(500)) { samples -> emit(samples) }
                    }
                }
            }
        }
    }

    private fun ensureVoiceLoaded(): NativePiperVoice {
        nativeVoice?.let { return it }
        val selected = PiperVoiceCatalog.find(voiceId)
        check(isVoiceInstalled(selected.id)) {
            "La voz ${selected.name} no está instalada. Descárgala desde la sección Voces."
        }
        val piper = piperInstance ?: PiperJNI().also { instance ->
            instance.initialize(true)
            piperInstance = instance
        }
        return piper.loadVoice(PiperVoiceCatalog.modelFile(selected.id), PiperVoiceCatalog.configFile(selected.id))
            .also { nativeVoice = it }
    }

    override suspend fun stop() {
        generation.incrementAndGet()
        withContext(Dispatchers.IO) { DesktopAudioPlayer.stop() }
    }

    fun release() {
        generation.incrementAndGet()
        runCatching { nativeVoice?.close() }
        nativeVoice = null
        runCatching { piperInstance?.close() }
        piperInstance = null
    }

    companion object {
        private const val MIN_MODEL_BYTES = 20L * 1024L * 1024L
        private const val MAX_MODEL_BYTES = 512L * 1024L * 1024L
        private const val MAX_CONFIG_BYTES = 5L * 1024L * 1024L
        private const val ALD_SHA256 = "019b3803293c93e34a206dd2e53a3889209a514e786fd7144f7b70196c579b63"
        private val downloadClient = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()

        fun isVoiceInstalled(id: String): Boolean {
            val voice = PiperVoiceCatalog.find(id)
            val model = PiperVoiceCatalog.modelFile(voice.id)
            val config = PiperVoiceCatalog.configFile(voice.id)
            if (!Files.isRegularFile(model) || !Files.isRegularFile(config)) return false
            if (runCatching { Files.size(model) }.getOrDefault(0L) < MIN_MODEL_BYTES) return false
            if (runCatching { Files.size(config) }.getOrDefault(0L) < 100L) return false
            return runCatching { validateConfig(config) }.getOrDefault(false)
        }

        suspend fun downloadVoice(id: String, onProgress: (Int, String) -> Unit = { _, _ -> }) =
            withContext(Dispatchers.IO) {
                val voice = PiperVoiceCatalog.find(id)
                val model = PiperVoiceCatalog.modelFile(voice.id)
                val config = PiperVoiceCatalog.configFile(voice.id)
                Files.createDirectories(PiperVoiceCatalog.voiceDirectory)
                if (!isVoiceInstalled(voice.id)) {
                    if (voice.directDownload) {
                        val modelUrl = voice.modelUrl ?: throw IOException("Falta la URL del modelo Piper.")
                        val configUrl = voice.configUrl ?: throw IOException("Falta la URL de configuración Piper.")
                        val hash = voice.expectedSha256 ?: if (voice.id == "es_MX-ald-medium") ALD_SHA256 else null
                        downloadFile(modelUrl, model, MAX_MODEL_BYTES, hash) { p -> onProgress(p, "Descargando modelo") }
                        downloadFile(configUrl, config, MAX_CONFIG_BYTES, null) { p -> onProgress(p, "Descargando configuración") }
                    } else {
                        val archive = model.resolveSibling("${voice.id}.tar.gz")
                        try {
                            val url = voice.archiveUrl ?: throw IOException("Falta la URL del paquete de voz.")
                            downloadFile(url, archive, MAX_MODEL_BYTES, null) { p -> onProgress(p, "Descargando paquete de voz") }
                            onProgress(100, "Instalando modelo")
                            extractVoiceArchive(archive, model, config)
                        } finally {
                            Files.deleteIfExists(archive)
                            Files.deleteIfExists(archive.resolveSibling(archive.fileName.toString() + ".part"))
                        }
                    }
                }
                check(isVoiceInstalled(voice.id)) {
                    "La voz ${voice.name} no superó la validación. El modelo o su configuración están incompletos."
                }
                onProgress(100, "Voz instalada")
            }

        suspend fun deleteVoice(id: String) = withContext(Dispatchers.IO) {
            val voice = PiperVoiceCatalog.find(id)
            val model = PiperVoiceCatalog.modelFile(voice.id)
            val config = PiperVoiceCatalog.configFile(voice.id)
            listOf(model, config, model.resolveSibling(model.fileName.toString() + ".part"),
                config.resolveSibling(config.fileName.toString() + ".part")).forEach { Files.deleteIfExists(it) }
            check(!isVoiceInstalled(voice.id)) { "No se pudo eliminar completamente la voz." }
        }

        private fun downloadFile(
            url: String,
            target: Path,
            maxBytes: Long,
            expectedSha256: String?,
            onProgress: (Int) -> Unit
        ) {
            val temp = target.resolveSibling(target.fileName.toString() + ".part")
            val request = Request.Builder().url(url).header("User-Agent", "TikLBot-Windows").header("Accept", "*/*").build()
            try {
                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Descarga Piper respondió HTTP ${response.code}.")
                    val body = response.body ?: throw IOException("Descarga Piper vacía.")
                    val total = body.contentLength()
                    if (total > maxBytes) throw IOException("El archivo Piper supera el tamaño máximo permitido.")
                    var downloaded = 0L
                    val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
                    body.byteStream().use { input ->
                        Files.newOutputStream(temp).buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                downloaded += count
                                if (downloaded > maxBytes) throw IOException("El archivo Piper supera el tamaño máximo permitido.")
                                output.write(buffer, 0, count)
                                digest?.update(buffer, 0, count)
                                if (total > 0L) onProgress(((downloaded * 100L) / total).toInt().coerceIn(0, 99))
                            }
                        }
                    }
                    if (downloaded < 100L) throw IOException("Archivo Piper vacío o incompleto.")
                    if (expectedSha256 != null) {
                        val actual = digest!!.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                        if (!actual.equals(expectedSha256, true)) throw IOException("El modelo Piper no coincide con su SHA-256.")
                    }
                }
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
                onProgress(100)
            } finally {
                Files.deleteIfExists(temp)
            }
        }

        private fun extractVoiceArchive(archive: Path, model: Path, config: Path) {
            val modelTemp = model.resolveSibling(model.fileName.toString() + ".part")
            val configTemp = config.resolveSibling(config.fileName.toString() + ".part")
            var foundModel = false
            var foundConfig = false
            try {
                TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive.toFile())))).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (!entry.isFile || entry.size <= 0L) continue
                        val name = entry.name.substringAfterLast('/')
                        when {
                            name.endsWith(".onnx", true) && !name.endsWith(".encoder.onnx", true) && !name.endsWith(".decoder.onnx", true) -> {
                                if (entry.size > MAX_MODEL_BYTES) throw IOException("Modelo del paquete demasiado grande.")
                                copyTarEntry(tar, modelTemp, entry.size)
                                foundModel = true
                            }
                            name.endsWith(".onnx.json", true) -> {
                                if (entry.size > MAX_CONFIG_BYTES) throw IOException("Configuración del paquete demasiado grande.")
                                copyTarEntry(tar, configTemp, entry.size)
                                foundConfig = true
                            }
                        }
                    }
                }
                if (!foundModel || !foundConfig) throw IOException("El paquete no contiene el modelo ONNX y su JSON.")
                if (Files.size(modelTemp) < MIN_MODEL_BYTES || !validateConfig(configTemp)) {
                    throw IOException("El paquete Piper no superó la validación.")
                }
                Files.move(modelTemp, model, StandardCopyOption.REPLACE_EXISTING)
                Files.move(configTemp, config, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(modelTemp)
                Files.deleteIfExists(configTemp)
            }
        }

        private fun copyTarEntry(tar: TarArchiveInputStream, target: Path, size: Long) {
            Files.newOutputStream(target).buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var remaining = size
                while (remaining > 0L) {
                    val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw IOException("Archivo Piper truncado.")
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }

        private fun validateConfig(path: Path): Boolean {
            val json = JSONObject(Files.readString(path))
            val language = json.optJSONObject("language")?.optString("code").orEmpty().replace('-', '_').lowercase()
            val validLanguage = language.matches(Regex("^[a-z]{2,3}([_-][a-z0-9]{2,8})*$"))
            val rate = json.optJSONObject("audio")?.optInt("sample_rate", 0) ?: 0
            return validLanguage && rate in 8_000..48_000 && json.optJSONObject("phoneme_id_map") != null
        }
    }
}

class DynamicPiperSpeechEngine(initial: ReaderSettings) : SpeechEngine {
    private data class Config(val voiceId: String, val volume: Float, val speed: Float, val randomVoices: Boolean)
    private val config = AtomicReference(toConfig(initial))
    private val engines = ConcurrentHashMap<String, PiperSpeechEngine>()
    private val lock = Mutex()
    @Volatile private var muted = false

    fun update(settings: ReaderSettings) {
        val next = toConfig(settings)
        config.set(next)
        engines[next.voiceId]?.updatePlaybackSettings(next.volume, next.speed)
    }

    fun setMuted(value: Boolean) {
        muted = value
        engines.values.forEach { it.setMuted(value) }
    }

    override suspend fun initialize() {
        lock.withLock {
            val current = config.get()
            engineFor(current.voiceId, current).initialize()
        }
    }

    override suspend fun speak(text: String) {
        lock.withLock {
            val current = config.get()
            val voiceId = if (current.randomVoices) {
                val language = PiperVoiceCatalog.find(current.voiceId).language
                val installed = PiperVoiceCatalog.voices.filter { it.language == language }
                    .map { it.id }.filter { PiperSpeechEngine.isVoiceInstalled(it) }
                if (installed.isNotEmpty()) installed[Random.nextInt(installed.size)] else current.voiceId
            } else current.voiceId
            engineFor(voiceId, current).speak(text)
        }
    }

    override suspend fun stop() { engines.values.forEach { runCatching { it.stop() } } }

    fun release() {
        engines.values.forEach { runCatching { it.release() } }
        engines.clear()
    }

    private fun engineFor(id: String, current: Config): PiperSpeechEngine {
        val engine = engines.getOrPut(id) { PiperSpeechEngine(id, current.volume, current.speed) }
        engine.updatePlaybackSettings(current.volume, current.speed)
        engine.setMuted(muted)
        if (engines.size > 2) {
            engines.keys.firstOrNull { it != id }?.let { engines.remove(it)?.release() }
        }
        return engine
    }

    private fun toConfig(settings: ReaderSettings) = Config(
        PiperVoiceCatalog.find(settings.voiceId).id,
        settings.volume.coerceIn(0f, 1f),
        settings.speed.coerceIn(0.65f, 1.5f),
        settings.randomVoices
    )
}
