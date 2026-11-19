package com.myclivebot.core

import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Single speech consumer with three bounded lanes:
 * LIVE comments first, event alerts second, and other speech last.
 * Audio already in progress is never interrupted.
 */
class SpeechQueueWorker(
    private val engine: SpeechEngine,
    scope: CoroutineScope,
    capacity: Int = 30,
    private val priorityCapacity: Int = 16,
    private val onSpoken: (String) -> Unit = {},
    private val onCommentQueueFull: (Long) -> Unit = {}
) {
    private val lock = Any()
    private data class QueuedSpeech(
        val text: String,
        val itemId: String?,
        val isComment: Boolean = false,
        val enqueuedAtNanos: Long = System.nanoTime()
    )
    private val commentQueue = ArrayDeque<QueuedSpeech>()
    private val priorityQueue = ArrayDeque<QueuedSpeech>()
    private val normalQueue = ArrayDeque<QueuedSpeech>()
    // Keep the visible queue-size setting meaningful: accepted comments are
    // retained FIFO, but stale chat cannot accumulate an oversized hidden backlog.
    private val commentCapacity = capacity.coerceIn(5, 100)
    private val normalCapacity = capacity.coerceAtLeast(1)
    private val importantCapacity = priorityCapacity.coerceAtLeast(1)
    private val priorityBurstLimit = 3
    private val commentBurstLimit = 10
    private var consecutivePriority = 0
    private var consecutiveComments = 0
    private var rejectedComments = 0L
    private var commentOverflowWarningActive = false
    private var closed = false
    private val lastCommentQueueWaitMs = AtomicLong(0L)
    private val maxCommentQueueWaitMs = AtomicLong(0L)
    private val spokenCommentCount = AtomicLong(0L)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            while (isActive) {
                val next = synchronized(lock) {
                    when {
                        commentQueue.isNotEmpty() &&
                            (priorityQueue.isEmpty() || consecutiveComments < commentBurstLimit) -> {
                            consecutivePriority = 0
                            consecutiveComments++
                            commentQueue.removeFirst().also {
                                if (commentQueue.size <= commentCapacity / 2) commentOverflowWarningActive = false
                            }
                        }
                        normalQueue.isNotEmpty() &&
                            (priorityQueue.isEmpty() || consecutivePriority >= priorityBurstLimit) -> {
                            consecutivePriority = 0
                            normalQueue.removeFirst()
                        }
                        priorityQueue.isNotEmpty() -> {
                            consecutiveComments = 0
                            consecutivePriority++
                            priorityQueue.removeFirst()
                        }
                        normalQueue.isNotEmpty() -> {
                            consecutivePriority = 0
                            consecutiveComments = 0
                            normalQueue.removeFirst()
                        }
                        else -> null
                    }
                }
                if (next == null) {
                    if (wake.receiveCatching().getOrNull() == null) break
                    continue
                }
                if (next.isComment) {
                    val waitMs = TimeUnit.NANOSECONDS.toMillis(
                        (System.nanoTime() - next.enqueuedAtNanos).coerceAtLeast(0L)
                    )
                    lastCommentQueueWaitMs.set(waitMs)
                    maxCommentQueueWaitMs.accumulateAndGet(waitMs) { current, candidate -> maxOf(current, candidate) }
                    spokenCommentCount.incrementAndGet()
                }
                try {
                    engine.speak(next.text)
                    onSpoken(next.text)
                } catch (_: Exception) {
                    // A failed utterance must not kill the worker or block later comments.
                }
            }
        }
    }

    /**
     * Comments use their own highest-priority lane. Event alerts use priority=true;
     * other auxiliary speech stays in the normal lane.
     */
    fun enqueue(
        text: String,
        priority: Boolean = false,
        itemId: String? = null,
        comment: Boolean = false
    ): Boolean {
        if (text.isBlank()) return false
        var overflowNotice: Long? = null
        val accepted = synchronized(lock) {
            if (closed) return@synchronized false
            when {
                comment -> {
                    if (commentQueue.size >= commentCapacity) {
                        rejectedComments++
                        if (!commentOverflowWarningActive) {
                            commentOverflowWarningActive = true
                            overflowNotice = rejectedComments
                        }
                        false
                    } else {
                        commentQueue.addLast(QueuedSpeech(text, itemId, isComment = true))
                        true
                    }
                }
                priority -> {
                    if (priorityQueue.size >= importantCapacity) false else {
                        priorityQueue.addLast(QueuedSpeech(text, itemId))
                        true
                    }
                }
                else -> {
                    if (normalQueue.size >= normalCapacity) normalQueue.removeFirst()
                    normalQueue.addLast(QueuedSpeech(text, itemId))
                    true
                }
            }
        }
        if (accepted) wake.trySend(Unit)
        overflowNotice?.let { count -> runCatching { onCommentQueueFull(count) } }
        return accepted
    }

    /**
     * Atomically queues a welcome (when present) together with its originating
     * comment so a welcome can never consume the final slot and strand the chat.
     * Each pair is speech text to optional comment ID.
     */
    fun enqueueComments(items: List<Pair<String, String?>>): Boolean {
        val validItems = items.filter { it.first.isNotBlank() }
        if (validItems.isEmpty()) return false
        var overflowNotice: Long? = null
        val accepted = synchronized(lock) {
            if (closed) return@synchronized false
            if (commentQueue.size + validItems.size > commentCapacity) {
                rejectedComments++
                if (!commentOverflowWarningActive) {
                    commentOverflowWarningActive = true
                    overflowNotice = rejectedComments
                }
                false
            } else {
                validItems.forEach { (text, itemId) ->
                    commentQueue.addLast(QueuedSpeech(text, itemId, isComment = true))
                }
                true
            }
        }
        if (accepted) wake.trySend(Unit)
        overflowNotice?.let { count -> runCatching { onCommentQueueFull(count) } }
        return accepted
    }

    /** Removes one pending chat utterance by its originating LIVE comment ID. */
    fun skipComment(itemId: String): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val queued = commentQueue.firstOrNull { it.itemId == itemId }
            ?: normalQueue.firstOrNull { it.itemId == itemId }
            ?: return@synchronized false
        val removed = commentQueue.remove(queued) || normalQueue.remove(queued)
        if (removed && commentQueue.size <= commentCapacity / 2) commentOverflowWarningActive = false
        removed
    }

    /** Drops pending chat comments while preserving all event alerts and auxiliary speech. */
    fun clearPendingComments(): Int = synchronized(lock) {
        if (closed) return@synchronized 0
        val removed = commentQueue.size
        commentQueue.clear()
        commentOverflowWarningActive = false
        removed
    }

    fun queuedCount(): Int = synchronized(lock) {
        commentQueue.size + priorityQueue.size + normalQueue.size
    }

    /** Number of new comments rejected at the hard backlog limit; accepted comments are never evicted. */
    fun rejectedCommentCount(): Long = synchronized(lock) { rejectedComments }

    /** Last and maximum queue wait before comment speech began, in milliseconds. */
    fun commentQueueLatencyMs(): Triple<Long, Long, Long> = Triple(
        lastCommentQueueWaitMs.get(), maxCommentQueueWaitMs.get(), spokenCommentCount.get()
    )

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            commentQueue.clear()
            priorityQueue.clear()
            normalQueue.clear()
        }
        wake.close()
    }
}
