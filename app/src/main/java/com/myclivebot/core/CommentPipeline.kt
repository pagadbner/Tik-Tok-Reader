package com.myclivebot.core

import java.util.concurrent.atomic.AtomicReference

/** Incoming TikTok events are filtered against the latest settings without reconnecting. */
class CommentPipeline(
    private val filter: CommentFilter,
    private val speechQueue: SpeechQueueWorker,
    initialSettings: ReaderSettings
) {
    private val settings = AtomicReference(initialSettings)

    fun updateSettings(next: ReaderSettings) {
        settings.set(next)
    }

    fun onComment(comment: LiveComment): Boolean {
        val current = settings.get()
        if (!filter.shouldRead(comment, current)) return false
        val speech = filter.toSpeech(comment, current)
        return speechQueue.enqueue(speech)
    }

    fun onEvent(event: LiveEvent): Boolean {
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
        return speechQueue.enqueue(speech)
    }
}
