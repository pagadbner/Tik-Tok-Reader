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

        // Commands are local speech responses only; this app does not send chat messages.
        val commandResponse = if (current.customCommandsEnabled) {
            ChatCommandParser.responseFor(comment.text, comment.displayName, current.customCommands, comment.isModerator)
        } else null
        val speechComment = if (commandResponse != null) comment.copy(text = commandResponse) else comment
        val speechSettings = if (commandResponse != null) current.copy(onlyUsername = false) else current
        val speech = filter.toSpeech(speechComment, speechSettings)
        val queued = speechQueue.enqueue(speech)
        if (queued) commentsQueued.incrementAndGet()
        return queued
    }

    fun onEvent(event: LiveEvent): Boolean {
        eventsReceived.incrementAndGet()
        val current = settings.get()
        val speech = when (event.type) {
            LiveEventType.FOLLOW -> if (current.readFollows) {
                "${event.displayName} te ha seguido"
            } else ""
            LiveEventType.GIFT -> GiftAlertRules.render(event, current).orEmpty()
        }
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
