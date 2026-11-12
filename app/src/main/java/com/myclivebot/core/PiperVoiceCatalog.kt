package com.myclivebot.core

/** Downloadable Piper voices. Community models are optional and are not bundled in the APK. */
data class PiperVoice(
    val id: String,
    val name: String,
    val description: String,
    val official: Boolean,
    val modelUrl: String? = null,
    val configUrl: String? = null,
    val archiveUrl: String? = null
) {
    val licenseNote: String
        get() = if (official) "Modelo del catálogo Piper; consulta su MODEL_CARD."
        else "Modelo comunitario sin licencia de redistribución verificada; uso opcional bajo tu criterio."
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
            id = "es_MX-ald-medium",
            name = "Ald · Media",
            description = "Voz mexicana oficial Piper · calidad medium",
            official = true,
            modelUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_MX/ald/medium/es_MX-ald-medium.onnx?download=true",
            configUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_MX/ald/medium/es_MX-ald-medium.onnx.json?download=true"
        ),
        PiperVoice(
            id = "es_MX-alan-medium",
            name = "Alan · Comunidad",
            description = "Voz mexicana comunitaria · experimental",
            official = false,
            archiveUrl = "https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/es/es_MX-alan-medium.tar.gz?download=true"
        ),
        PiperVoice(
            id = "es_MX-alicia-medium",
            name = "Alicia · Comunidad",
            description = "Voz mexicana comunitaria · experimental",
            official = false,
            archiveUrl = "https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/es/es_MX-alicia-medium.tar.gz?download=true"
        ),
        PiperVoice(
            id = "es_MX-carla-medium",
            name = "Carla · Comunidad",
            description = "Voz mexicana comunitaria · experimental",
            official = false,
            archiveUrl = "https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/es/es_MX-carla-medium.tar.gz?download=true"
        ),
        PiperVoice(
            id = "es_MX-gabriela-medium",
            name = "Gabriela · Comunidad",
            description = "Voz mexicana comunitaria · experimental",
            official = false,
            archiveUrl = "https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/es/es_MX-gabriela-medium.tar.gz?download=true"
        ),
        PiperVoice(
            id = "es_MX-mario-medium",
            name = "Mario · Comunidad",
            description = "Voz mexicana comunitaria · experimental",
            official = false,
            archiveUrl = "https://huggingface.co/AIHeaven/piper_unofficial_voices/resolve/main/es/es_MX-mario-medium.tar.gz?download=true"
        ),
        PiperVoice(
            id = "es_MX-mireya-medium",
            name = "Mireya · Comunidad",
            description = "Voz mexicana comunitaria · experimental",
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