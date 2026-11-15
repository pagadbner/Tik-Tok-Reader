package com.myclivebot.core

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class LivePipelineStats(
    val commentsReceived: Long,
    val commentsFiltered: Long,
    val commentsQueued: Long,
    val eventsReceived: Long,
    val eventsQueued: Long,
    val speechQueueSize: Int
)

/** Incoming TikTok events are filtered against the latest settings without reconnecting. */
class CommentPipeline(
    private val filter: CommentFilter,
    private val speechQueue: SpeechQueueWorker,
    initialSettings: ReaderSettings
) {
    private val settings = AtomicReference(initialSettings)
    private val welcomeTracker = WelcomeMessageTracker()
    private val shareAlertTracker = ShareAlertTracker()
    private val likeAlertTracker = LikeAlertTracker()
    private val speakerTracker = CommentSpeakerTracker()
    private val commentsReceived = AtomicLong()
    private val commentsFiltered = AtomicLong()
    private val commentsQueued = AtomicLong()
    private val eventsReceived = AtomicLong()
    private val eventsQueued = AtomicLong()

    fun updateSettings(next: ReaderSettings) {
        settings.set(next)
    }

    fun onComment(comment: LiveComment): Boolean {
        commentsReceived.incrementAndGet()
        val current = settings.get()
        if (!filter.shouldRead(comment, current)) {
            commentsFiltered.incrementAndGet()
            return false
        }
        // A welcome is emitted once per accepted commenter in this connection.
        welcomeTracker.messageFor(comment, current)?.let { welcome ->
            val welcomeSpeech = filter.toSpeech(
                comment.copy(text = welcome),
                current.copy(readUsername = false, onlyUsername = false)
            )
            speechQueue.enqueue(welcomeSpeech)
        }

        // A speaker is named once when they take the floor. Consecutive comments
        // from that same person omit the name until another accepted speaker appears.
        val announceSpeaker = speakerTracker.isNewSpeaker(comment)

        // Commands are local speech responses only; this app does not send chat messages.
        val commandResponse = if (current.customCommandsEnabled) {
            ChatCommandParser.responseFor(comment.text, comment.displayName, current.customCommands, comment.isModerator)
        } else null
        val replyTarget = comment.replyToDisplayName?.takeIf { it.isNotBlank() }
        val speechComment = when {
            commandResponse != null -> comment.copy(text = commandResponse, replyToDisplayName = null)
            replyTarget != null -> {
                val replyMention = Regex("(?i)@${Regex.escape(replyTarget)}(?![\\p{L}\\p{N}_])")
                val cleanReplyText = comment.text.replace(replyMention, "")
                    .replace(Regex("\\s+"), " ").trim()
                val safeSpeaker = ChatCommandParser.spokenUsername(comment.displayName)
                val safeTarget = ChatCommandParser.spokenUsername(replyTarget)
                comment.copy(
                    text = if (cleanReplyText.isBlank()) {
                        "${safeSpeaker} le respondió a ${safeTarget}"
                    } else {
                        "${safeSpeaker} le respondió a ${safeTarget}: ${cleanReplyText}"
                    },
                    replyToDisplayName = null
                )
            }
            else -> comment
        }
        val speechSettings = current.copy(
            onlyUsername = if (commandResponse != null || replyTarget != null) false else current.onlyUsername,
            readUsername = current.readUsername && announceSpeaker && replyTarget == null
        )
        val speech = filter.toSpeech(speechComment, speechSettings)
        val queued = speechQueue.enqueue(speech, itemId = comment.id)
        if (queued) commentsQueued.incrementAndGet()
        return queued
    }

    fun onEvent(event: LiveEvent): Boolean {
        eventsReceived.incrementAndGet()
        val current = settings.get()
        val speech = when (event.type) {
            LiveEventType.FOLLOW -> if (current.readFollows) {
                "${ChatCommandParser.spokenUsername(event.displayName)} te ha seguido"
            } else ""
            LiveEventType.GIFT -> GiftAlertRules.render(event, current).orEmpty()
            LiveEventType.SHARE -> shareAlertTracker.render(event, current).orEmpty()
            LiveEventType.JOIN -> if (current.readJoins &&
                (!current.joinFollowerOnly || event.isFollower == true)
            ) {
                "@${ChatCommandParser.spokenUsername(event.displayName).removePrefix("@")} se ha unido al live"
            } else ""
            LiveEventType.LIKE -> likeAlertTracker.render(event, current).orEmpty()
        }
        // Ignore disabled/empty alerts instead of passing blank utterances to any provider.
        if (speech.isBlank()) return false
        // Alerts use the priority lane so they are not buried under chat backlog.
        val queued = speechQueue.enqueue(speech, priority = true)
        if (queued) eventsQueued.incrementAndGet()
        return queued
    }

    fun stats(): LivePipelineStats = LivePipelineStats(
        commentsReceived = commentsReceived.get(),
        commentsFiltered = commentsFiltered.get(),
        commentsQueued = commentsQueued.get(),
        eventsReceived = eventsReceived.get(),
        eventsQueued = eventsQueued.get(),
        speechQueueSize = speechQueue.queuedCount()
    )
}


/**
 * Tracks the current voice turn in the LIVE chat. The same user keeps the floor
 * until another accepted commenter speaks; returning after an interruption is
 * treated as a new turn and their name is announced again.
 */
class CommentSpeakerTracker {
    private var lastSpeakerKey: String? = null

    @Synchronized
    fun isNewSpeaker(comment: LiveComment): Boolean {
        val key = comment.userId.trim().takeIf { it.isNotBlank() }
            ?: comment.displayName.trim().lowercase()
        if (key.isBlank()) return false
        val changed = key != lastSpeakerKey
        lastSpeakerKey = key
        return changed
    }
}
