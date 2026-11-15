package com.myclivebot.core

data class EdgeTtsVoice(val id: String, val name: String, val description: String)

object EdgeTtsVoiceCatalog {
    const val DEFAULT_VOICE_ID = "es-MX-DaliaNeural"
    val voices = listOf(
        EdgeTtsVoice("es-MX-DaliaNeural", "Dalia · México", "Español mexicano · femenina"),
        EdgeTtsVoice("es-MX-JorgeNeural", "Jorge · México", "Español mexicano · masculina"),
        EdgeTtsVoice("es-ES-ElviraNeural", "Elvira · España", "Español de España · femenina"),
        EdgeTtsVoice("es-ES-AlvaroNeural", "Álvaro · España", "Español de España · masculina"),
        EdgeTtsVoice("en-US-AriaNeural", "Aria · English US", "Inglés estadounidense · femenina"),
        EdgeTtsVoice("en-US-GuyNeural", "Guy · English US", "Inglés estadounidense · masculina"),
        EdgeTtsVoice("en-US-JennyNeural", "Jenny · English US", "Inglés estadounidense · femenina"),
        EdgeTtsVoice("en-GB-SoniaNeural", "Sonia · English UK", "Inglés británico · femenina")
    )
    fun find(id: String): EdgeTtsVoice = voices.firstOrNull { it.id == id } ?: voices.first()
}
