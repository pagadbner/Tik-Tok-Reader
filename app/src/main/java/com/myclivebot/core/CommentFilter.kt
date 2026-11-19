package com.myclivebot.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    val readShares: Boolean = true,
    val shareFollowerOnly: Boolean = false,
    val readJoins: Boolean = true,
    val joinFollowerOnly: Boolean = false,
    val readLikes: Boolean = true,
    val randomVoices: Boolean = false,
    val customCommandsEnabled: Boolean = false,
    val customCommands: Map<String, String> = emptyMap(),
    val welcomeEnabled: Boolean = false,
    val welcomeTemplate: String = "¡Bienvenido al LIVE, {user}!",
    val giftMinimumRepeatCount: Int = 1,
    val giftAlertTemplate: String = "",
    val useProfileUsername: Boolean = false
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
    private data class FilterRules(
        val sourceSettings: ReaderSettings,
        val ignoredUsers: Set<String>,
        val blockedWords: List<Regex>
    )

    private val lastByUser = ConcurrentHashMap<String, Pair<String, Long>>()
    private val lastCleanupAt = AtomicLong(0L)
    @Volatile private var cachedRules: FilterRules? = null
    private val whitespaceRegex = Regex("\\s+")
    private val urlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

    private fun rulesFor(settings: ReaderSettings): FilterRules {
        cachedRules?.takeIf { it.sourceSettings === settings }?.let { return it }
        return synchronized(this) {
            cachedRules?.takeIf { it.sourceSettings === settings } ?: FilterRules(
                sourceSettings = settings,
                ignoredUsers = settings.ignoredUsers.map {
                    it.trim().removePrefix("@").lowercase(java.util.Locale.ROOT)
                }.filter { it.isNotEmpty() }.toSet(),
                blockedWords = settings.blockedWords.mapNotNull { word ->
                    val needle = word.trim().lowercase(java.util.Locale.ROOT)
                    needle.takeIf { it.isNotEmpty() }?.let {
                        Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(it)}(?![\\p{L}\\p{N}_])")
                    }
                }
            ).also { cachedRules = it }
        }
    }

    private fun cleanupDuplicatesIfNeeded() {
        if (lastByUser.size <= 2_000) return
        val now = System.currentTimeMillis()
        val previous = lastCleanupAt.get()
        if (now - previous < 30_000L || !lastCleanupAt.compareAndSet(previous, now)) return
        val cutoff = now - 60_000L
        lastByUser.entries.removeIf { it.value.second < cutoff }
    }

    fun shouldRead(
        comment: LiveComment,
        settings: ReaderSettings,
        recordDuplicate: Boolean = true
    ): Boolean {
        if (!CommentLanguageFilter.isSupported(comment.text, comment.contentLanguage)) return false
        if (settings.followerOnlyChat && comment.isFollower != true) return false
        val userKey = comment.userId.ifBlank { comment.displayName }.trim().lowercase()
        val rules = rulesFor(settings)
        val ignored = rules.ignoredUsers
        val displayKey = comment.displayName.trim().removePrefix("@").lowercase()
        val profileKey = comment.profileUsername.trim().removePrefix("@").lowercase()
        if (userKey in ignored || displayKey in ignored || profileKey in ignored) return false
        if (settings.ignoreModerators && comment.isModerator) return false

        val sourceToFilter = if (settings.onlyUsername) {
            if (settings.useProfileUsername) comment.profileUsername.ifBlank { comment.displayName }
            else comment.displayName
        } else comment.text
        val cleanText = sourceToFilter.trim().replace(whitespaceRegex, " ")
        if (cleanText.isBlank() || cleanText.length > settings.maxCommentLength.coerceAtLeast(1)) return false
        val normalized = cleanText.lowercase(java.util.Locale.ROOT)
        if (rules.blockedWords.any { it.containsMatchIn(normalized) }) return false

        val now = comment.receivedAt
        val duplicateWindow = settings.suppressDuplicatesSeconds.coerceAtLeast(0) * 1_000
        val previous = lastByUser[userKey]
        val isDuplicate = previous != null && previous.first == normalized &&
            now >= previous.second && now - previous.second <= duplicateWindow
        if (isDuplicate) return false
        if (recordDuplicate) {
            lastByUser[userKey] = normalized to now
            cleanupDuplicatesIfNeeded()
        }
        return true
    }

    /** Commit duplicate-suppression state only after the speech queue accepts a comment. */
    fun confirmRead(comment: LiveComment, settings: ReaderSettings) {
        val userKey = comment.userId.ifBlank { comment.displayName }.trim().lowercase()
        val sourceToFilter = if (settings.onlyUsername) {
            if (settings.useProfileUsername) comment.profileUsername.ifBlank { comment.displayName }
            else comment.displayName
        } else comment.text
        val normalized = sourceToFilter.trim().replace(whitespaceRegex, " ")
            .lowercase(java.util.Locale.ROOT)
        if (userKey.isNotBlank() && normalized.isNotBlank()) {
            lastByUser[userKey] = normalized to comment.receivedAt
            cleanupDuplicatesIfNeeded()
        }
    }

    fun usernameForSpeech(comment: LiveComment, settings: ReaderSettings): String =
        usernameForSpeech(comment.displayName, comment.profileUsername, settings)

    fun usernameForSpeech(displayName: String, profileUsername: String, settings: ReaderSettings): String {
        val fallbackName = sanitizeUsername(displayName.take(80))
        return if (settings.useProfileUsername) {
            sanitizeProfileUsername(profileUsername.take(80)).ifBlank { fallbackName }
        } else {
            fallbackName
        }
    }

    fun toSpeech(comment: LiveComment, settings: ReaderSettings): String {
        val safeName = usernameForSpeech(comment, settings)
        if (settings.onlyUsername) return safeName

        var safeText = comment.text.trim()
            .replace(whitespaceRegex, " ")
            .replace(urlRegex, " enlace ")
        safeText = sanitize(safeText, settings)
        if (safeText.isBlank()) return if (settings.readUsername && safeName.isNotBlank()) "$safeName dice" else ""
        val spokenName = if (settings.readUsername && safeName.isNotBlank()) "$safeName dice: " else ""
        return spokenName + safeText
    }

    /** TikTok handles allow letters, digits, dots and underscores; discard other symbols. */
    private fun sanitizeProfileUsername(input: String): String {
        val safe = buildString(input.length) {
            input.codePoints().forEach { codePoint ->
                if (Character.isLetterOrDigit(codePoint) || codePoint == '_'.code || codePoint == '.'.code) {
                    appendCodePoint(codePoint)
                }
            }
        }.trim('.')
        return if (safe.codePoints().anyMatch { Character.isLetterOrDigit(it) }) safe else ""
    }

    /** Preserve the display-name fallback; use "usuario" only when it has no words. */
    private fun sanitizeUsername(input: String): String =
        ChatCommandParser.spokenUsername(input)

    private fun sanitize(input: String, settings: ReaderSettings): String {
        val output = StringBuilder(input.length)
        input.codePoints().forEach { codePoint ->
            if (settings.ignoreEmojis && isEmoji(codePoint)) return@forEach
            if (settings.ignoreSymbols && !Character.isLetterOrDigit(codePoint) && !Character.isWhitespace(codePoint)) {
                return@forEach
            }
            output.appendCodePoint(codePoint)
        }
        return output.toString().replace(whitespaceRegex, " ").trim()
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
