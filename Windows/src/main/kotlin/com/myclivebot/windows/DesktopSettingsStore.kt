package com.myclivebot.windows

import com.myclivebot.core.ReaderSettings
import com.myclivebot.core.EdgeTtsVoiceCatalog
import com.myclivebot.core.PiperVoiceCatalog
import com.myclivebot.core.TikTokTtsVoiceCatalog
import java.util.prefs.Preferences

object DesktopSettingsStore {
    private val prefs = Preferences.userRoot().node("com/myclivebot/tiktokreader/windows").also { current ->
        // Keep existing settings from earlier Windows builds after the product rename.
        runCatching {
            val legacy = Preferences.userRoot().node("com/myclivebot/tiklbot/windows")
            if (current.keys().isEmpty()) {
                legacy.keys().forEach { key -> legacy.get(key, null)?.let { current.put(key, it) } }
                current.flush()
            }
        }
    }

    fun load(): ReaderSettings {
        val customCommands = buildMap {
            repeat(40) { index ->
                val entry = prefs.get("custom_command_$index", "")
                val separator = entry.indexOf('=')
                if (separator > 0) {
                    val command = entry.substring(0, separator).trim()
                    val response = entry.substring(separator + 1).trim()
                    if (command.isNotBlank() && response.isNotBlank()) put(command, response)
                }
            }
        }

        return ReaderSettings(
            readUsername = prefs.getBoolean("read_username", true),
            ignoreModerators = prefs.getBoolean("ignore_moderators", false),
            blockedWords = prefs.get("blocked_words", "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            ignoredUsers = prefs.get("ignored_users", "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            maxQueueSize = prefs.getInt("max_queue", 30).coerceIn(5, 100),
            suppressDuplicatesSeconds = prefs.getLong("suppress_duplicates_seconds", 5L).coerceIn(0L, 120L),
            maxCommentLength = prefs.getInt("max_comment_length", 300).coerceIn(1, 2000),
            ignoreEmojis = prefs.getBoolean("ignore_emojis", true),
            ignoreSymbols = prefs.getBoolean("ignore_symbols", false),
            onlyUsername = prefs.getBoolean("only_username", false),
            volume = prefs.getFloat("volume", 0.9f).coerceIn(0f, 1f),
            speed = prefs.getFloat("speed", 1f).coerceIn(0.65f, 1.5f),
            voiceId = prefs.get("voice_id", PiperVoiceCatalog.DEFAULT_VOICE_ID),
            speechProvider = prefs.get("speech_provider", "edge"),
            apiVoiceId = prefs.get("api_voice_id", "es_mx_002"),
            apiRandomVoice = prefs.getBoolean("api_random_voice", false),
            tikFinityChannelId = prefs.get("tikfinity_channel_id", "0"),
            edgeVoiceId = prefs.get("edge_voice_id", EdgeTtsVoiceCatalog.DEFAULT_VOICE_ID),
            tikTokVoiceId = prefs.get("tiktok_voice_id", TikTokTtsVoiceCatalog.DEFAULT_VOICE_ID),
            followerOnlyChat = prefs.getBoolean("follower_only_chat", false),
            readFollows = prefs.getBoolean("read_follows", true),
            readGifts = prefs.getBoolean("read_gifts", true),
            readShares = prefs.getBoolean("read_shares", true),
            shareFollowerOnly = prefs.getBoolean("share_follower_only", false),
            readJoins = prefs.getBoolean("read_joins", true),
            joinFollowerOnly = prefs.getBoolean("join_follower_only", false),
            readLikes = prefs.getBoolean("read_likes", true),
            randomVoices = prefs.getBoolean("random_voices", false),
            customCommandsEnabled = prefs.getBoolean("custom_commands_enabled", false),
            customCommands = customCommands,
            welcomeEnabled = prefs.getBoolean("welcome_enabled", false),
            welcomeTemplate = prefs.get("welcome_template", "¡Bienvenido al LIVE, {user}!"),
            giftMinimumRepeatCount = prefs.getInt("gift_minimum_repeat_count", 1).coerceIn(1, 999),
            giftAlertTemplate = prefs.get("gift_alert_template", ""),
            useProfileUsername = prefs.getBoolean("use_profile_username", false)
        )
    }

    fun save(settings: ReaderSettings) {
        prefs.putBoolean("read_username", settings.readUsername)
        prefs.putBoolean("ignore_moderators", settings.ignoreModerators)
        prefs.put("blocked_words", settings.blockedWords.joinToString("\n"))
        prefs.put("ignored_users", settings.ignoredUsers.joinToString("\n"))
        prefs.putInt("max_queue", settings.maxQueueSize.coerceIn(5, 100))
        prefs.putLong("suppress_duplicates_seconds", settings.suppressDuplicatesSeconds.coerceIn(0L, 120L))
        prefs.putInt("max_comment_length", settings.maxCommentLength.coerceIn(1, 2000))
        prefs.putBoolean("ignore_emojis", settings.ignoreEmojis)
        prefs.putBoolean("ignore_symbols", settings.ignoreSymbols)
        prefs.putBoolean("only_username", settings.onlyUsername)
        prefs.putFloat("volume", settings.volume.coerceIn(0f, 1f))
        prefs.putFloat("speed", settings.speed.coerceIn(0.65f, 1.5f))
        prefs.put("voice_id", settings.voiceId)
        prefs.put("speech_provider", settings.speechProvider)
        prefs.put("api_voice_id", settings.apiVoiceId)
        prefs.putBoolean("api_random_voice", settings.apiRandomVoice)
        prefs.put("tikfinity_channel_id", settings.tikFinityChannelId)
        prefs.put("edge_voice_id", settings.edgeVoiceId)
        prefs.put("tiktok_voice_id", settings.tikTokVoiceId)
        prefs.putBoolean("follower_only_chat", settings.followerOnlyChat)
        prefs.putBoolean("read_follows", settings.readFollows)
        prefs.putBoolean("read_gifts", settings.readGifts)
        prefs.putBoolean("read_shares", settings.readShares)
        prefs.putBoolean("share_follower_only", settings.shareFollowerOnly)
        prefs.putBoolean("read_joins", settings.readJoins)
        prefs.putBoolean("join_follower_only", settings.joinFollowerOnly)
        prefs.putBoolean("read_likes", settings.readLikes)
        prefs.putBoolean("random_voices", settings.randomVoices)
        prefs.putBoolean("custom_commands_enabled", settings.customCommandsEnabled)
        // Store each command separately: Preferences limits a single value to 8 KB.
        runCatching { prefs.keys().filter { it.startsWith("custom_command_") }.forEach { prefs.remove(it) } }
        settings.customCommands.entries.take(40).forEachIndexed { index, entry ->
            prefs.put("custom_command_$index", "${entry.key}=${entry.value.take(280)}")
        }
        prefs.putBoolean("welcome_enabled", settings.welcomeEnabled)
        prefs.put("welcome_template", settings.welcomeTemplate)
        prefs.putInt("gift_minimum_repeat_count", settings.giftMinimumRepeatCount.coerceIn(1, 999))
        prefs.put("gift_alert_template", settings.giftAlertTemplate)
        prefs.putBoolean("use_profile_username", settings.useProfileUsername)
        prefs.flush()
    }
}
