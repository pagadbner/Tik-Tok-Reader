package com.myclivebot.core

import java.util.concurrent.ConcurrentHashMap

/** User-configurable reading, moderation and speech settings. */
data class ReaderSettings(
    val readUsername: Boolean = true,
    val ignoreModerators: Boolean = false,
    val blockedWords: Set<String> = emptySet(),
    val ignoredUsers: Set<String> = emptySet(),
    val maxQueueSize: Int = 30,
    val suppressDuplicatesSeconds: Long = 5,
    val maxCommentLength: Int = 300,
    val ignoreEmojis: Boolean = true,
    val ignoreSymbols: Boolean = false,
    val onlyUsername: Boolean = false,
    val volume: Float = 1.0f,
    val speed: Float = 1.0f,
    val voiceId: String = PiperVoiceCatalog.DEFAULT_VOICE_ID,
    val speechProvider: String = "piper",
    val apiVoiceId: String = "es_mx_002",
    val apiRandomVoice: Boolean = false,
    val tikFinityChannelId: String = "0",
    val edgeVoiceId: String = EdgeTtsVoiceCatalog.DEFAULT_VOICE_ID,
    val tikTokVoiceId: String = TikTokTtsVoiceCatalog.DEFAULT_VOICE_ID,
    val followerOnlyChat: Boolean = false,
    val readFollows: Boolean = true,
    val readGifts: Boolean = true,
    val randomVoices: Boolean = false,
    val customCommandsEnabled: Boolean = false,
    val customCommands: Map<String, String> = emptyMap(),
    val welcomeEnabled: Boolean = false,
    val welcomeTemplate: String = "¡Bienvenido al LIVE, {user}!",
    val giftMinimumRepeatCount: Int = 1,
    val giftAlertTemplate: String = ""
)

/**
 * Keep speech limited to Latin-script comments (including Spanish and English).
 * Non-Latin writing systems such as Arabic, Cyrillic, Indic, CJK and others are
 * ignored without relying on unreliable language guessing for short chat messages.
 */
object CommentLanguageFilter {
    fun isSupported(text: String, languageCode: String? = null): Boolean {
        val language = languageCode?.trim()
            ?.substringBefore('-')
            ?.substringBefore('_')
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it != "und" }
        if (language != null && language !in setOf("es", "en")) return false

        var supported = true
        text.codePoints().forEach { codePoint ->
            if (Character.isLetter(codePoint) &&
                Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN
            ) supported = false
        }
        return supported
    }
}

class CommentFilter {
    private val lastByUser = ConcurrentHashMap<String, Pair<String, Long>>()

    fun shouldRead(comment: LiveComment, settings: ReaderSettings): Boolean {
        if (!CommentLanguageFilter.isSupported(comment.text, comment.contentLanguage)) return false
        if (settings.followerOnlyChat && comment.isFollower != true) return false
        val userKey = comment.userId.ifBlank { comment.displayName }.trim().lowercase()
        val ignored = settings.ignoredUsers.map { it.trim().removePrefix("@").lowercase() }
            .filter { it.isNotEmpty() }.toSet()
        if (userKey in ignored || comment.displayName.trim().removePrefix("@").lowercase() in ignored) return false
        if (settings.ignoreModerators && comment.isModerator) return false

        val sourceToFilter = if (settings.onlyUsername) comment.displayName else comment.text
        val cleanText = sourceToFilter.trim().replace(Regex("\\s+"), " ")
        if (cleanText.isBlank() || cleanText.length > settings.maxCommentLength.coerceAtLeast(1)) return false
        val normalized = cleanText.lowercase()
        if (settings.blockedWords.any { word ->
                val needle = word.trim().lowercase()
                needle.isNotEmpty() && normalized.contains(needle)
            }) return false

        val now = comment.receivedAt
        val duplicateWindow = settings.suppressDuplicatesSeconds.coerceAtLeast(0) * 1_000
        var isDuplicate = false
        lastByUser.compute(userKey) { _, previous ->
            if (previous != null && previous.first == normalized &&
                now >= previous.second && now - previous.second <= duplicateWindow
            ) {
                isDuplicate = true
                previous
            } else normalized to now
        }
        if (isDuplicate) return false
        if (lastByUser.size > 2_000) {
            val cutoff = now - 60_000
            lastByUser.entries.removeIf { it.value.second < cutoff }
        }
        return true
    }

    fun toSpeech(comment: LiveComment, settings: ReaderSettings): String {
        val safeName = sanitizeUsername(comment.displayName.take(80))
        if (settings.onlyUsername) return safeName

        var safeText = comment.text.trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), " enlace ")
        safeText = sanitize(safeText, settings)
        if (safeText.isBlank()) return if (settings.readUsername && safeName.isNotBlank()) "$safeName dice" else ""
        val spokenName = if (settings.readUsername && safeName.isNotBlank()) "$safeName dice: " else ""
        return spokenName + safeText
    }

    /** Keeps letters and meaningful spaces only; digits, emoji and decorative symbols are never spoken. */
    private fun sanitizeUsername(input: String): String {
        val output = StringBuilder(input.length)
        var pendingSpace = false
        input.codePoints().forEach { codePoint ->
            when {
                Character.isLetter(codePoint) -> {
                    if (pendingSpace && output.isNotEmpty()) output.append(' ')
                    output.appendCodePoint(codePoint)
                    pendingSpace = false
                }
                Character.isWhitespace(codePoint) -> pendingSpace = output.isNotEmpty()
            }
        }
        return output.toString().trim()
    }

    private fun sanitize(input: String, settings: ReaderSettings): String {
        val output = StringBuilder(input.length)
        input.codePoints().forEach { codePoint ->
            if (settings.ignoreEmojis && isEmoji(codePoint)) return@forEach
            if (settings.ignoreSymbols && !Character.isLetterOrDigit(codePoint) && !Character.isWhitespace(codePoint)) {
                return@forEach
            }
            output.appendCodePoint(codePoint)
        }
        return output.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun isEmoji(codePoint: Int): Boolean {
        return codePoint in 0x1F000..0x1FAFF ||
            codePoint in 0x2600..0x27BF ||
            codePoint in 0x2300..0x23FF ||
            codePoint in 0xFE00..0xFE0F ||
            codePoint in 0x1F1E6..0x1F1FF ||
            codePoint in 0x1F3FB..0x1F3FF ||
            codePoint == 0x200D ||
            codePoint == 0x20E3
    }
}
