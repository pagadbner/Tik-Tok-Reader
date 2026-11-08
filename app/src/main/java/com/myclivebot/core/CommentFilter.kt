package com.myclivebot.core

import java.util.concurrent.ConcurrentHashMap

/** User-configurable moderation plus a small anti-spam memory. */
data class ReaderSettings(
    val readUsername: Boolean = true,
    val ignoreModerators: Boolean = false,
    val blockedWords: Set<String> = emptySet(),
    val ignoredUsers: Set<String> = emptySet(),
    val maxQueueSize: Int = 30,
    val suppressDuplicatesSeconds: Long = 5,
    val maxCommentLength: Int = 300
)

class CommentFilter {
    private val lastByUser = ConcurrentHashMap<String, Pair<String, Long>>()

    fun shouldRead(comment: LiveComment, settings: ReaderSettings): Boolean {
        val userKey = comment.userId.ifBlank { comment.displayName }.trim().lowercase()
        val ignored = settings.ignoredUsers.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (userKey in ignored || comment.displayName.trim().lowercase() in ignored) return false
        if (settings.ignoreModerators && comment.isModerator) return false

        val cleanText = comment.text.trim().replace(Regex("\\s+"), " ")
        if (cleanText.isBlank() || cleanText.length > settings.maxCommentLength.coerceAtLeast(1)) return false
        val normalized = cleanText.lowercase()
        if (settings.blockedWords.any { word ->
                val needle = word.trim().lowercase()
                needle.isNotEmpty() && normalized.contains(needle)
            }) return false

        val now = comment.receivedAt
        val previous = lastByUser.put(userKey, normalized to now)
        val duplicateWindow = settings.suppressDuplicatesSeconds.coerceAtLeast(0) * 1_000
        if (previous != null && previous.first == normalized && now - previous.second in 0..duplicateWindow) {
            return false
        }
        // Keep the anti-spam cache bounded for long-running LIVE sessions.
        if (lastByUser.size > 2_000) {
            val cutoff = now - 60_000
            lastByUser.entries.removeIf { it.value.second < cutoff }
        }
        return true
    }

    fun toSpeech(comment: LiveComment, settings: ReaderSettings): String {
        val safeName = comment.displayName.ifBlank { "Usuario" }.take(40)
        val safeText = comment.text.trim().replace(Regex("\\s+"), " ")
            .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), " enlace ")
        return (if (settings.readUsername) "$safeName dice: " else "") + safeText
    }
}
