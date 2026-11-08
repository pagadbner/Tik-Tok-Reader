package com.myclivebot.core

/** Central path: incoming TikTok event -> moderation -> normalized speech -> FIFO queue. */
class CommentPipeline(
    private val filter: CommentFilter,
    private val speechQueue: SpeechQueueWorker,
    private val settings: ReaderSettings
) {
    fun onComment(comment: LiveComment): Boolean {
        if (!filter.shouldRead(comment, settings)) return false
        return speechQueue.enqueue(filter.toSpeech(comment, settings))
    }
}
