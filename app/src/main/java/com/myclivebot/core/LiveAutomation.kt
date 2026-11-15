package com.myclivebot.core

import java.util.LinkedHashMap
import java.util.Locale

/**
 * Parses a deliberately small, non-executable command table.
 * Format: !command=spoken response. No network/chat-send action is performed.
 */
object ChatCommandParser {
    private val commandPattern = Regex("^![a-z0-9_]{1,24}$")

    fun parseConfig(raw: String): Map<String, String> {
        val parsed = LinkedHashMap<String, String>()
        raw.lineSequence().take(40).forEach { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val command = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            val response = line.substring(separator + 1).trim().take(280)
            if (commandPattern.matches(command) && response.isNotBlank()) {
                parsed[command] = response
            }
        }
        return parsed
    }

    fun responseFor(message: String, username: String, commands: Map<String, String>, isModerator: Boolean = false): String? {
        if (!isModerator) return null
        val command = message.trim().split(Regex("\\s+"), limit = 2).firstOrNull()
            ?.lowercase(Locale.ROOT)
            ?: return null
        val template = commands[command] ?: return null
        val safeName = spokenUsername(username).ifBlank { "usuario" }
        return template
            .replace("{user}", safeName, ignoreCase = true)
            .replace("{command}", command, ignoreCase = true)
            .trim()
            .take(280)
            .takeIf { it.isNotBlank() }
    }

    fun spokenUsername(input: String): String {
        val normalized = java.text.Normalizer.normalize(input.trim(), java.text.Normalizer.Form.NFC)
        if (normalized.isBlank()) return "usuario"

        // Remove emoji code points while preserving readable words, numbers and username punctuation.
        val readable = buildString {
            normalized.codePoints().forEach { codePoint ->
                if (!isEmojiCodePoint(codePoint)) appendCodePoint(codePoint)
            }
        }.trim()
        val hasWordCharacter = readable.codePoints().anyMatch { Character.isLetterOrDigit(it) }
        return if (hasWordCharacter) readable else "usuario"
    }
    
    private fun isEmojiCodePoint(codePoint: Int): Boolean =
        codePoint in 0x1F000..0x1FAFF ||
            (codePoint in 0x2600..0x27BF && codePoint != 0x2726) ||
            codePoint in 0x2300..0x23FF ||
            codePoint in 0xFE00..0xFE0F ||
            codePoint in 0x1F1E6..0x1F1FF ||
            codePoint in 0x1F3FB..0x1F3FF ||
            codePoint == 0x200D ||
            codePoint == 0x20E3
}

/**
 * Welcomes a viewer once per connection, when their first accepted chat comment arrives.
 * It does not claim to detect TikTok room-entry/join events.
 */
class WelcomeMessageTracker {
    private val welcomedUsers = HashSet<String>()

    @Synchronized
    fun messageFor(comment: LiveComment, settings: ReaderSettings): String? {
        if (!settings.welcomeEnabled) return null
        val key = comment.userId.ifBlank { comment.displayName }.trim().lowercase(Locale.ROOT)
        if (key.isBlank()) return null
        val name = ChatCommandParser.spokenUsername(comment.displayName).ifBlank { "usuario" }
        val message = settings.welcomeTemplate
            .replace("{user}", name, ignoreCase = true)
            .trim()
            .take(280)
        if (message.isBlank() || !welcomedUsers.add(key)) return null
        return message
    }
}

/**
 * Gift metadata currently available to the app is the repeat count encoded in the
 * existing Webcast event detail (for example, "x5"). Gift names and diamond values
 * are intentionally not fabricated.
 */
object GiftAlertRules {
    fun repeatCount(event: LiveEvent): Int =
        event.detail.removePrefix("x").toIntOrNull()?.coerceAtLeast(1) ?: 1

    fun shouldAnnounce(event: LiveEvent, settings: ReaderSettings): Boolean =
        event.type == LiveEventType.GIFT &&
            settings.readGifts &&
            repeatCount(event) >= settings.giftMinimumRepeatCount.coerceAtLeast(1)

    fun render(event: LiveEvent, settings: ReaderSettings): String? {
        if (!shouldAnnounce(event, settings)) return null
        val name = ChatCommandParser.spokenUsername(event.displayName).ifBlank { "usuario" }
        val count = repeatCount(event)
        val defaultTemplate = if (count == 1) "{user} te envió un regalo" else "{user} te envió {count} regalos"
        val template = settings.giftAlertTemplate.ifBlank { defaultTemplate }
        return template
            .replace("{user}", name, ignoreCase = true)
            .replace("{count}", count.toString(), ignoreCase = true)
            .trim()
            .take(280)
            .takeIf { it.isNotBlank() }
    }

}

/** Limits share announcements to two per user during one LIVE session. */
class ShareAlertTracker {
    private val announcementsByUser = HashMap<String, Int>()

    @Synchronized
    fun render(event: LiveEvent, settings: ReaderSettings): String? {
        if (event.type != LiveEventType.SHARE || !settings.readShares) return null
        if (settings.shareFollowerOnly && event.isFollower != true) return null
        val key = event.userId.trim().ifBlank { event.displayName.trim() }.lowercase(Locale.ROOT)
        if (key.isBlank()) return null
        val sent = announcementsByUser[key] ?: 0
        if (sent >= MAX_ANNOUNCEMENTS_PER_USER) return null
        announcementsByUser[key] = sent + 1
        val name = ChatCommandParser.spokenUsername(event.displayName)
        return "@${name.removePrefix("@")} ha compartido el live"
    }

    companion object {
        const val MAX_ANNOUNCEMENTS_PER_USER = 2
    }
}

/** Accumulates likes per user for the current LIVE and rate-limits thanks to once per 10 minutes. */
class LikeAlertTracker {
    private data class UserLikes(
        var total: Long = 0L,
        var lastAnnouncementAt: Long = Long.MIN_VALUE,
        var previousAnnouncementAt: Long = Long.MIN_VALUE,
        var pendingAnnouncementAt: Long? = null
    )
    private val likesByUser = HashMap<String, UserLikes>()

    @Synchronized
    fun render(event: LiveEvent, settings: ReaderSettings): String? {
        if (event.type != LiveEventType.LIKE || !settings.readLikes) return null
        val key = userKey(event)
        if (key.isBlank()) return null
        val increment = event.detail.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
        val state = likesByUser.getOrPut(key) { UserLikes() }
        state.total = if (Long.MAX_VALUE - state.total < increment) Long.MAX_VALUE else state.total + increment
        val now = event.receivedAt
        if (state.total < LIKES_THRESHOLD || state.pendingAnnouncementAt != null) return null
        if (state.lastAnnouncementAt != Long.MIN_VALUE &&
            now - state.lastAnnouncementAt < COOLDOWN_MS
        ) return null

        // Reserve the alert until the priority queue confirms that it accepted it.
        state.previousAnnouncementAt = state.lastAnnouncementAt
        state.lastAnnouncementAt = now
        state.pendingAnnouncementAt = now
        val name = ChatCommandParser.spokenUsername(event.displayName)
        return "Gracias por dar me gusta @${name.removePrefix("@")}, tu apoyo es importante"
    }

    @Synchronized
    fun confirmQueued(event: LiveEvent) {
        val state = likesByUser[userKey(event)] ?: return
        if (state.pendingAnnouncementAt == event.receivedAt) {
            state.pendingAnnouncementAt = null
            state.previousAnnouncementAt = Long.MIN_VALUE
        }
    }

    @Synchronized
    fun rollbackIfNotQueued(event: LiveEvent) {
        val state = likesByUser[userKey(event)] ?: return
        if (state.pendingAnnouncementAt == event.receivedAt) {
            state.lastAnnouncementAt = state.previousAnnouncementAt
            state.previousAnnouncementAt = Long.MIN_VALUE
            state.pendingAnnouncementAt = null
        }
    }

    private fun userKey(event: LiveEvent): String =
        event.userId.trim().ifBlank { event.displayName.trim() }.lowercase(Locale.ROOT)

    companion object {
        const val LIKES_THRESHOLD = 1_000L
        const val COOLDOWN_MS = 10 * 60 * 1_000L
    }
}
