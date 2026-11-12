package com.myclivebot.core

/** Downloadable Piper voices. Community models are optional and are not bundled in the APK. */
data class PiperVoice(
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
) {
}

object PiperVoiceCatalog {
    val voices = listOf(
        PiperVoice(
            id = "es_MX-claude-high",
            name = "Claude · Alta calidad",
            description = "Voz mexicana oficial Piper · calidad high",
            official = true,
            modelUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_MX/claude/high/es_MX-claude-high.onnx?download=true",
            configUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_MX/claude/high/es_MX-claude-high.onnx.json?download=true"
        ),
        PiperVoice(
            id = "es_MX-cortana-19669-epoch-high",
            name = "Cortana · Halo (México)",
            description = "Piper ONNX · español latino · variante pública de HirCoir",
            official = false,
            directDownload = true,
            modelUrl = "https://huggingface.co/spaces/HirCoir/Piper-TTS-Spanish/resolve/main/es_MX-cortana-19669-epoch-high.onnx",
            configUrl = "https://huggingface.co/spaces/HirCoir/Piper-TTS-Spanish/resolve/main/es_MX-cortana-19669-epoch-high.onnx.json",
            expectedSha256 = "f4871e05fdb55e56f85877c9c9ef1279faa879e2dfaa707e7ebcb81d2f6c2f8b",
            licenseNote = "Modelo comunitario publicado por HirCoir; el modelo de la ficha CE-Legacy que compartiste es otra variante y requiere aceptar acceso en Hugging Face."
        ),
        PiperVoice(
            id = "en_us-jarvis-ucm-high",
            name = "Jarvis · MCU (English)",
            description = "Piper ONNX · inglés estadounidense · descarga aproximada de 114 MB",
            official = false,
            directDownload = true,
            modelUrl = "https://huggingface.co/spaces/HirCoir/Piper-TTS-Spanish/resolve/main/en_us-jarvis_ucm-high.onnx",
            configUrl = "https://huggingface.co/spaces/HirCoir/Piper-TTS-Spanish/resolve/main/en_us-jarvis_ucm-high.onnx.json",
            expectedSha256 = "cfda5b06b59bfcf25d74b1dbabbb4e14fcc6451d53fa2f4fbbefd398f787b5d8",
            language = "en",
            testPhrase = "Hello. I am Jarvis. The live reader is ready.",
            licenseNote = "Modelo comunitario publicado por HirCoir; licencia de redistribución no indicada en la ficha consultada."
        ),
        PiperVoice(
            id = "es_MX-ald-medium",
            name = "Ald · Media",
            description = "Voz mexicana oficial Piper · calidad medium",
            official = true,
            modelUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_MX/ald/medium/es_MX-ald-medium.onnx?download=true",
            configUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_MX/ald/medium/es_MX-ald-medium.onnx.json?download=true"
        ),
        PiperVoice(
            id = "es_ES-davefx-medium",
            name = "DaveFX · Español de España",
            description = "Voz oficial Piper · calidad medium · licencia por revisar",
            official = true,
            modelUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/davefx/medium/es_ES-davefx-medium.onnx?download=true",
            configUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/davefx/medium/es_ES-davefx-medium.onnx.json?download=true"
        ),
        PiperVoice(
            id = "es_ES-sharvard-medium",
            name = "Sharvard · Español de España",
            description = "Voz oficial Piper · calidad medium · licencia por revisar",
            official = true,
            modelUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/sharvard/medium/es_ES-sharvard-medium.onnx?download=true",
            configUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/sharvard/medium/es_ES-sharvard-medium.onnx.json?download=true"
        ),
        PiperVoice(
            id = "es_AR-daniela-high",
            name = "Daniela · Español argentino",
            description = "Voz oficial Piper · calidad high · licencia por revisar",
            official = true,
            modelUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_AR/daniela/high/es_AR-daniela-high.onnx?download=true",
            configUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_AR/daniela/high/es_AR-daniela-high.onnx.json?download=true"
        ),
        PiperVoice(
            id = "es_MX-alan-medium",
            name = "Alan · Comunidad",
            description = "Voz comunitaria en español · experimental (MX/ES)",
            official = false,
            archiveUrl = "https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/es/es_MX-alan-medium.tar.gz?download=true"
        ),
        PiperVoice(
            id = "es_MX-alicia-medium",
            name = "Alicia · Comunidad",
            description = "Voz comunitaria en español · experimental (MX/ES)",
            official = false,
            archiveUrl = "https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/es/es_MX-alicia-medium.tar.gz?download=true"
        ),
        PiperVoice(
            id = "es_MX-carla-medium",
            name = "Carla · Comunidad",
            description = "Voz comunitaria en español · experimental (MX/ES)",
            official = false,
            archiveUrl = "https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/es/es_MX-carla-medium.tar.gz?download=true"
        ),
        PiperVoice(
            id = "es_MX-gabriela-medium",
            name = "Gabriela · Comunidad",
            description = "Voz comunitaria en español · experimental (MX/ES)",
            official = false,
            archiveUrl = "https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/es/es_MX-gabriela-medium.tar.gz?download=true"
        ),
        PiperVoice(
            id = "es_MX-mario-medium",
            name = "Mario · Comunidad",
            description = "Voz comunitaria en español · experimental (MX/ES)",
            official = false,
            archiveUrl = "https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/es/es_MX-mario-medium.tar.gz?download=true"
        ),
        PiperVoice(
            id = "es_MX-mireya-medium",
            name = "Mireya · Comunidad",
            description = "Voz comunitaria en español · experimental (MX/ES)",
            official = false,
            archiveUrl = "https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/es/es_MX-mireya-medium.tar.gz?download=true"
        )
    )

    const val DEFAULT_VOICE_ID = "es_MX-claude-high"
    fun find(id: String): PiperVoice = voices.firstOrNull { it.id == id }
        ?: voices.first { it.id == DEFAULT_VOICE_ID }
    fun modelFile(context: android.content.Context, id: String) =
        java.io.File(context.filesDir, "voices/$id.onnx")
    fun configFile(context: android.content.Context, id: String) =
        java.io.File(context.filesDir, "voices/$id.onnx.json")
}