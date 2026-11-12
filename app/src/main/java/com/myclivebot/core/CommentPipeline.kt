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
        val speech = filter.toSpeech(comment, current)
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
            LiveEventType.GIFT -> if (current.readGifts) {
                val detail = event.detail.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
                "${event.displayName} te envió un regalo$detail"
            } else ""
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
