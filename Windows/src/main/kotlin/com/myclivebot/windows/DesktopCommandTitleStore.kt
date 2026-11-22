package com.myclivebot.windows

import java.util.prefs.Preferences

/** Persists command display titles separately from the shared trigger/response model. */
object DesktopCommandTitleStore {
    private val preferences = Preferences.userRoot().node("com/myclivebot/tiktokreader/windows/command_titles")

    @Synchronized
    fun load(): Map<String, String> = buildMap {
        repeat(40) { index ->
            val entry = preferences.get("command_$index", "")
            val separator = entry.indexOf('=')
            if (separator > 0) {
                val trigger = entry.substring(0, separator).trim()
                val title = entry.substring(separator + 1).trim()
                if (trigger.isNotBlank() && title.isNotBlank()) put(trigger, title)
            }
        }
    }

    @Synchronized
    fun save(titles: Map<String, String>) {
        runCatching {
            preferences.keys().filter { it.startsWith("command_") }.forEach(preferences::remove)
        }
        titles.entries.take(40).forEachIndexed { index, entry ->
            preferences.put("command_$index", "${entry.key}=${entry.value.take(40)}")
        }
        runCatching { preferences.flush() }
    }
}
