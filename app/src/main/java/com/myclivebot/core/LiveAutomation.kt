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

        // Keep the complete display name, including emojis and decorative symbols.
        // Only names made entirely of symbols/whitespace (no letters or digits) use the fallback.
        val hasWordCharacter = normalized.codePoints().anyMatch { Character.isLetterOrDigit(it) }
        return if (hasWordCharacter) normalized else "usuario"
    }
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
