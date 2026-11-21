package com.myclivebot.windows

import com.myclivebot.core.ReaderSettings
import java.util.prefs.Preferences

object DesktopSettingsStore {
    private val prefs = Preferences.userRoot().node("com/myclivebot/tiklbot/windows")

    fun load(): ReaderSettings = ReaderSettings(
        readUsername = prefs.getBoolean("read_username", true),
        ignoreModerators = prefs.getBoolean("ignore_moderators", false),
        blockedWords = prefs.get("blocked_words", "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        ignoredUsers = prefs.get("ignored_users", "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        maxQueueSize = prefs.getInt("max_queue", 30).coerceIn(5, 100),
        maxCommentLength = prefs.getInt("max_comment_length", 300).coerceIn(1, 2000),
        ignoreEmojis = prefs.getBoolean("ignore_emojis", true),
        ignoreSymbols = prefs.getBoolean("ignore_symbols", false),
        onlyUsername = prefs.getBoolean("only_username", false),
        followerOnlyChat = prefs.getBoolean("follower_only_chat", false),
        readFollows = prefs.getBoolean("read_follows", true),
        readGifts = prefs.getBoolean("read_gifts", true),
        readShares = prefs.getBoolean("read_shares", true),
        readJoins = prefs.getBoolean("read_joins", true),
        readLikes = prefs.getBoolean("read_likes", true),
        speechProvider = "edge",
        edgeVoiceId = prefs.get("edge_voice_id", EdgeTtsVoiceCatalog.DEFAULT_VOICE_ID),
        volume = prefs.getFloat("volume", 0.9f).coerceIn(0f, 1f),
        speed = prefs.getFloat("speed", 1f).coerceIn(0.65f, 1.5f),
        welcomeEnabled = prefs.getBoolean("welcome_enabled", false),
        welcomeTemplate = prefs.get("welcome_template", "¡Bienvenido al LIVE, {user}!")
    )

    fun save(settings: ReaderSettings) {
        prefs.putBoolean("read_username", settings.readUsername)
        prefs.putBoolean("ignore_moderators", settings.ignoreModerators)
        prefs.put("blocked_words", settings.blockedWords.joinToString("\n"))
        prefs.put("ignored_users", settings.ignoredUsers.joinToString("\n"))
        prefs.putInt("max_queue", settings.maxQueueSize.coerceIn(5, 100))
        prefs.putInt("max_comment_length", settings.maxCommentLength.coerceIn(1, 2000))
        prefs.putBoolean("ignore_emojis", settings.ignoreEmojis)
        prefs.putBoolean("ignore_symbols", settings.ignoreSymbols)
        prefs.putBoolean("only_username", settings.onlyUsername)
        prefs.putBoolean("follower_only_chat", settings.followerOnlyChat)
        prefs.putBoolean("read_follows", settings.readFollows)
        prefs.putBoolean("read_gifts", settings.readGifts)
        prefs.putBoolean("read_shares", settings.readShares)
        prefs.putBoolean("read_joins", settings.readJoins)
        prefs.putBoolean("read_likes", settings.readLikes)
        prefs.put("edge_voice_id", settings.edgeVoiceId)
        prefs.putFloat("volume", settings.volume.coerceIn(0f, 1f))
        prefs.putFloat("speed", settings.speed.coerceIn(0.65f, 1.5f))
        prefs.putBoolean("welcome_enabled", settings.welcomeEnabled)
        prefs.put("welcome_template", settings.welcomeTemplate)
        prefs.flush()
    }
}
