package com.myclivebot.windows

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import java.util.prefs.Preferences

data class DesktopThemePalette(
    val id: String,
    val name: String,
    val description: String,
    val background: Color,
    val panel: Color,
    val primary: Color,
    val secondary: Color,
    val muted: Color,
    val surfaceVariant: Color,
    val glassBorder: Color,
    val gradient: List<Color>
)

object DesktopThemePresets {
    val all = listOf(
        DesktopThemePalette(
            id = "obsidian",
            name = "Obsidiana",
            description = "Negro monocromático y cristal humo",
            background = Color(0xFF07090D),
            panel = Color(0xB3101319),
            primary = Color(0xFF9AA3AE),
            secondary = Color(0xFFC1C8D1),
            muted = Color(0xFFA6AFBA),
            surfaceVariant = Color(0xFF20252D),
            glassBorder = Color(0x669BA5B1),
            gradient = listOf(Color(0xFF07090D), Color(0xFF11151C), Color(0xFF0A0D12), Color(0xFF171C24), Color(0xFF07090D))
        ),
        DesktopThemePalette(
            id = "graphite",
            name = "Grafito",
            description = "Carbón profundo con reflejos acero",
            background = Color(0xFF080B0D),
            panel = Color(0xB311171A),
            primary = Color(0xFF899A98),
            secondary = Color(0xFFB5C2BF),
            muted = Color(0xFFA5B0AE),
            surfaceVariant = Color(0xFF202A2B),
            glassBorder = Color(0x668CA19D),
            gradient = listOf(Color(0xFF080B0D), Color(0xFF11191B), Color(0xFF0B1113), Color(0xFF182123), Color(0xFF080B0D))
        ),
        DesktopThemePalette(
            id = "midnight",
            name = "Medianoche",
            description = "Negro azulado, tenue y elegante",
            background = Color(0xFF070A10),
            panel = Color(0xB3101520),
            primary = Color(0xFF8796B0),
            secondary = Color(0xFFB6C1D4),
            muted = Color(0xFFA5B0C2),
            surfaceVariant = Color(0xFF202A3A),
            glassBorder = Color(0x668D9DB9),
            gradient = listOf(Color(0xFF070A10), Color(0xFF101827), Color(0xFF0A101B), Color(0xFF182338), Color(0xFF070A10))
        ),
        DesktopThemePalette(
            id = "smoke",
            name = "Humo cálido",
            description = "Antracita con reflejos gris piedra",
            background = Color(0xFF0C0A09),
            panel = Color(0xB3181513),
            primary = Color(0xFFA99A8C),
            secondary = Color(0xFFC8BBAF),
            muted = Color(0xFFB4A99F),
            surfaceVariant = Color(0xFF2B2521),
            glassBorder = Color(0x66B09D8B),
            gradient = listOf(Color(0xFF0C0A09), Color(0xFF1B1512), Color(0xFF100D0B), Color(0xFF211A15), Color(0xFF0C0A09))
        )
    )

    fun byId(id: String): DesktopThemePalette =
        all.firstOrNull { it.id == id } ?: all.first()
}

val LocalDesktopTheme = staticCompositionLocalOf { DesktopThemePresets.all.first() }

object DesktopThemeStore {
    private val preferences = Preferences.userRoot().node("com/myclivebot/tiktokreader/windows/appearance")

    fun load(): DesktopThemePalette = DesktopThemePresets.byId(preferences.get("theme_id", "obsidian"))

    fun save(themeId: String) {
        preferences.put("theme_id", DesktopThemePresets.byId(themeId).id)
        runCatching { preferences.flush() }
    }
}
