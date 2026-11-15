package com.myclivebot.core

/** TikFinity voices are remote identifiers and are independent from the Piper catalog. */
data class TikFinityVoice(val id: String, val name: String, val description: String)

object TikFinityVoiceCatalog {
    const val DEFAULT_VOICE_ID = "es_mx_002"

    val voices = listOf(
        TikFinityVoice("es_mx_002", "Español México", "Voz en español mexicano"),
        TikFinityVoice("en_female_f08_salut_damour", "English · Salut d'amour", "Voz inglesa femenina"),
        TikFinityVoice("en_male_m03_lobby", "English · Lobby", "Voz inglesa masculina"),
        TikFinityVoice("en_female_f08_warmy_breeze", "English · Warmy Breeze", "Voz inglesa femenina"),
        TikFinityVoice("en_male_m03_sunshine_soon", "English · Sunshine Soon", "Voz inglesa masculina"),
        TikFinityVoice("en_random_singing", "Random singing", "Voz de canto aleatoria")
    )

    fun find(id: String): TikFinityVoice = voices.firstOrNull { it.id == id } ?: voices.first()
    fun randomVoice(): String = voices.filterNot { it.id == DEFAULT_VOICE_ID }.randomOrNull()?.id ?: DEFAULT_VOICE_ID
}
