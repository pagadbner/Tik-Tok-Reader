package com.myclivebot.core

data class TikTokTtsVoice(val id: String, val name: String, val description: String)

object TikTokTtsVoiceCatalog {
    const val DEFAULT_VOICE_ID = "es_mx_002"
    val voices = listOf(
        TikTokTtsVoice("es_mx_002", "Español México", "Voz mexicana"),
        TikTokTtsVoice("es_002", "Español", "Voz española"),
        TikTokTtsVoice("es_male_m3", "Español · Masculina", "Voz masculina"),
        TikTokTtsVoice("es_female_f6", "Español · Femenina", "Voz femenina"),
        TikTokTtsVoice("es_female_fp1", "Español · Femenina 2", "Voz femenina"),
        TikTokTtsVoice("es_mx_female_supermom", "Supermom", "Voz de personaje"),
        TikTokTtsVoice("es_mx_male_transformer", "Optimus Prime", "Voz de personaje"),
        TikTokTtsVoice("en_us_001", "English US · Female", "Inglés estadounidense"),
        TikTokTtsVoice("en_us_006", "English US · Male", "Inglés estadounidense"),
        TikTokTtsVoice("en_us_007", "English US · Female 2", "Inglés estadounidense"),
        TikTokTtsVoice("en_us_009", "English US · Male 2", "Inglés estadounidense"),
        TikTokTtsVoice("en_us_010", "English US · Male 3", "Inglés estadounidense"),
        TikTokTtsVoice("en_uk_001", "English UK · Male", "Inglés británico"),
        TikTokTtsVoice("en_uk_003", "English UK · Male 2", "Inglés británico"),
        TikTokTtsVoice("en_au_001", "English AU · Female", "Inglés australiano"),
        TikTokTtsVoice("en_au_002", "English AU · Male", "Inglés australiano"),
        TikTokTtsVoice("fr_001", "Français · Female", "Francés"),
        TikTokTtsVoice("fr_002", "Français · Male", "Francés"),
        TikTokTtsVoice("de_001", "Deutsch · Female", "Alemán"),
        TikTokTtsVoice("de_002", "Deutsch · Male", "Alemán"),
        TikTokTtsVoice("br_003", "Português BR · Female", "Portugués brasileño"),
        TikTokTtsVoice("br_004", "Português BR · Female 2", "Portugués brasileño"),
        TikTokTtsVoice("br_005", "Português BR · Male", "Portugués brasileño"),
        TikTokTtsVoice("id_001", "Bahasa Indonesia", "Indonesio"),
        TikTokTtsVoice("jp_001", "日本語 · Female", "Japonés"),
        TikTokTtsVoice("jp_003", "日本語 · Female 2", "Japonés"),
        TikTokTtsVoice("jp_005", "日本語 · Female 3", "Japonés"),
        TikTokTtsVoice("jp_006", "日本語 · Male", "Japonés"),
        TikTokTtsVoice("kr_002", "한국어 · Male", "Coreano"),
        TikTokTtsVoice("kr_003", "한국어 · Female", "Coreano"),
        TikTokTtsVoice("kr_004", "한국어 · Male 2", "Coreano"),
        TikTokTtsVoice("en_us_ghostface", "Ghostface", "Voz de personaje"),
        TikTokTtsVoice("en_us_chewbacca", "Chewbacca", "Voz de personaje"),
        TikTokTtsVoice("en_us_c3po", "C-3PO", "Voz de personaje"),
        TikTokTtsVoice("en_us_stitch", "Stitch", "Voz de personaje"),
        TikTokTtsVoice("en_us_stormtrooper", "Stormtrooper", "Voz de personaje"),
        TikTokTtsVoice("en_us_rocket", "Rocket", "Voz de personaje"),
        TikTokTtsVoice("en_female_f08_salut_damour", "Salut d'amour", "Voz cantada"),
        TikTokTtsVoice("en_male_m03_lobby", "Lobby", "Voz cantada"),
        TikTokTtsVoice("en_male_m03_sunshine_soon", "Sunshine Soon", "Voz cantada"),
        TikTokTtsVoice("en_female_f08_warmy_breeze", "Warmy Breeze", "Voz cantada"),
        TikTokTtsVoice("en_female_ht_f08_glorious", "Glorious", "Voz cantada"),
        TikTokTtsVoice("en_male_sing_funny_it_goes_up", "It Goes Up", "Voz cantada"),
        TikTokTtsVoice("en_male_m2_xhxs_m03_silly", "Chipmunk", "Voz de personaje"),
        TikTokTtsVoice("en_female_ht_f08_wonderful_world", "Wonderful World", "Voz cantada")
    )
    fun find(id: String): TikTokTtsVoice = voices.firstOrNull { it.id == id } ?: voices.first()
}
