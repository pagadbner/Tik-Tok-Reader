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

        // Strip emoji code points while preserving readable words, numbers and username punctuation.
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
    fun previewMessageFor(
        comment: LiveComment,
        settings: ReaderSettings,
        selectedUsername: String = ChatCommandParser.spokenUsername(comment.displayName)
    ): String? {
        if (!settings.welcomeEnabled) return null
        val key = userKey(comment)
        if (key.isBlank() || key in welcomedUsers) return null
        val name = selectedUsername.ifBlank { "usuario" }
        return settings.welcomeTemplate
            .replace("{user}", name, ignoreCase = true)
            .trim()
            .take(280)
            .takeIf { it.isNotBlank() }
    }

    /** Commits the welcome only after its speech and the first comment are both queued. */
    @Synchronized
    fun confirmQueued(comment: LiveComment) {
        userKey(comment).takeIf { it.isNotBlank() }?.let { welcomedUsers.add(it) }
    }

    /** Backwards-compatible eager helper for existing callers and tests. */
    @Synchronized
    fun messageFor(
        comment: LiveComment,
        settings: ReaderSettings,
        selectedUsername: String = ChatCommandParser.spokenUsername(comment.displayName)
    ): String? {
        val message = previewMessageFor(comment, settings, selectedUsername) ?: return null
        confirmQueued(comment)
        return message
    }

    private fun userKey(comment: LiveComment): String =
        comment.userId.ifBlank { comment.displayName }.trim().lowercase(Locale.ROOT)
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

    /** Renders one grouped announcement after the quiet window closes. */
    fun renderBatch(
        items: List<Pair<String, Long>>,
        totalCount: Long,
        additionalUsers: Int,
        settings: ReaderSettings
    ): String? {
        if (!settings.readGifts || totalCount <= 0L) return null
        val visibleItems = items.take(3)
        val names = visibleItems.map { (rawName, count) ->
            val name = ChatCommandParser.spokenUsername(rawName)
            if (count > 1L) "$name (x$count)" else name
        }.toMutableList()
        val hiddenUsers = (items.size - visibleItems.size).coerceAtLeast(0) + additionalUsers
        if (hiddenUsers == 1) names += "y otra persona"
        else if (hiddenUsers > 1) names += "y otras $hiddenUsers personas"
        val userSummary = names.joinToString(", ").ifBlank { "la comunidad" }
        val defaultTemplate = if (totalCount == 1L) {
            "Gracias {user} por tu regalo"
        } else {
            "Gracias {user} por los {count} regalos"
        }
        val template = settings.giftAlertTemplate.ifBlank { defaultTemplate }
        return template
            .replace("{user}", userSummary, ignoreCase = true)
            .replace("{count}", totalCount.toString(), ignoreCase = true)
            .trim()
            .take(280)
            .takeIf { it.isNotBlank() }
    }

}

/** Produces one concise thank-you for the distinct followers in a quiet window. */
object FollowAlertRules {
    fun renderBatch(names: List<String>, totalCount: Int): String? {
        if (totalCount <= 0) return null
        val visibleNames = names.take(3).map { ChatCommandParser.spokenUsername(it) }
        val hiddenCount = (totalCount - visibleNames.size).coerceAtLeast(0)
        val summary = buildList {
            addAll(visibleNames)
            if (hiddenCount == 1) add("y otra persona")
            else if (hiddenCount > 1) add("y otras $hiddenCount personas")
        }.joinToString(", ").ifBlank { "nuevas personas" }
        return "Gracias por seguirme, $summary"
    }
}

/** Limits share announcements to two per user during one LIVE session. */
class ShareAlertTracker {
    private data class UserShares(
        var confirmedCount: Int = 0,
        var pendingAt: Long? = null
    )
    private val sharesByUser = HashMap<String, UserShares>()

    @Synchronized
    fun render(event: LiveEvent, settings: ReaderSettings): String? {
        if (event.type != LiveEventType.SHARE || !settings.readShares) return null
        if (settings.shareFollowerOnly && event.isFollower != true) return null
        val key = userKey(event)
        if (key.isBlank()) return null
        val state = sharesByUser.getOrPut(key) { UserShares() }
        if (state.confirmedCount >= MAX_ANNOUNCEMENTS_PER_USER || state.pendingAt != null) return null
        // Reserve the slot; count it only after the priority speech queue accepts it.
        state.pendingAt = event.receivedAt
        val name = ChatCommandParser.spokenUsername(event.displayName)
        return "@${name.removePrefix("@")} ha compartido el live"
    }

    @Synchronized
    fun confirmQueued(event: LiveEvent) {
        val state = sharesByUser[userKey(event)] ?: return
        if (state.pendingAt == event.receivedAt) {
            state.pendingAt = null
            state.confirmedCount++
        }
    }

    @Synchronized
    fun rollbackIfNotQueued(event: LiveEvent) {
        val state = sharesByUser[userKey(event)] ?: return
        if (state.pendingAt == event.receivedAt) state.pendingAt = null
    }

    private fun userKey(event: LiveEvent): String =
        event.userId.trim().ifBlank { event.displayName.trim() }.lowercase(Locale.ROOT)

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
