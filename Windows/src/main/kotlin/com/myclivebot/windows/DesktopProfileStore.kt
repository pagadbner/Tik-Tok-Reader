package com.myclivebot.windows

import java.util.prefs.Preferences

data class DesktopRecentProfile(
    val username: String,
    val avatarUrl: String?
)

/** Keeps the same eight most-recent TikTok profiles used by the Android Home screen. */
object DesktopProfileStore {
    private const val MAX_PROFILES = 8
    private val preferences = Preferences.userRoot().node("com/myclivebot/tiktokreader/windows/profiles")

    @Synchronized
    fun load(): List<DesktopRecentProfile> = buildList {
        repeat(MAX_PROFILES) { index ->
            val username = preferences.get("profile_${index}_username", "").trim()
            if (username.isNotBlank()) {
                val avatar = preferences.get("profile_${index}_avatar", "")
                    .takeIf { it.startsWith("https://") }
                add(DesktopRecentProfile(username, avatar))
            }
        }
    }

    @Synchronized
    fun save(username: String, avatarUrl: String? = null): List<DesktopRecentProfile> {
        val clean = username.trim().removePrefix("@").substringBefore("/")
        require(clean.matches(Regex("[A-Za-z0-9._]{2,24}"))) { "Usuario de TikTok no válido." }
        val previous = load().firstOrNull { it.username.equals(clean, ignoreCase = true) }
        val next = (listOf(DesktopRecentProfile(clean, avatarUrl?.takeIf { it.startsWith("https://") } ?: previous?.avatarUrl)) +
            load().filterNot { it.username.equals(clean, ignoreCase = true) }).take(MAX_PROFILES)
        repeat(MAX_PROFILES) { index ->
            val profile = next.getOrNull(index)
            if (profile == null) {
                preferences.remove("profile_${index}_username")
                preferences.remove("profile_${index}_avatar")
            } else {
                preferences.put("profile_${index}_username", profile.username)
                preferences.put("profile_${index}_avatar", profile.avatarUrl.orEmpty())
            }
        }
        runCatching { preferences.flush() }
        return next
    }
}
