package com.myclivebot.core

import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    private val onSpoken: (String) -> Unit = {}
) {
    private val lock = Any()
    private data class QueuedSpeech(val text: String, val itemId: String?)
    private val commentQueue = ArrayDeque<QueuedSpeech>()
    private val priorityQueue = ArrayDeque<QueuedSpeech>()
    private val normalQueue = ArrayDeque<QueuedSpeech>()
    // Chat can arrive in bursts much faster than speech playback. Keep a larger,
    // still bounded backlog and never evict an already accepted comment.
    private val commentCapacity = (capacity.coerceIn(1, 100) * 20).coerceAtMost(2_000)
    private val normalCapacity = capacity.coerceAtLeast(1)
    private val importantCapacity = priorityCapacity.coerceAtLeast(1)
    private val priorityBurstLimit = 3
    private val commentBurstLimit = 10
    private var consecutivePriority = 0
    private var consecutiveComments = 0
    private var rejectedComments = 0L
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
                            commentQueue.removeFirst()
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
        val accepted = synchronized(lock) {
            when {
                comment -> {
                    if (commentQueue.size >= commentCapacity) {
                        rejectedComments++
                        false
                    } else {
                        commentQueue.addLast(QueuedSpeech(text, itemId))
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
        return accepted
    }

    /** Removes one pending chat utterance by its originating LIVE comment ID. */
    fun skipComment(itemId: String): Boolean = synchronized(lock) {
        val queued = commentQueue.firstOrNull { it.itemId == itemId }
            ?: normalQueue.firstOrNull { it.itemId == itemId }
            ?: return@synchronized false
        commentQueue.remove(queued) || normalQueue.remove(queued)
    }

    /** Drops pending chat comments while preserving all event alerts and auxiliary speech. */
    fun clearPendingComments(): Int = synchronized(lock) {
        val removed = commentQueue.size
        commentQueue.clear()
        removed
    }

    fun queuedCount(): Int = synchronized(lock) {
        commentQueue.size + priorityQueue.size + normalQueue.size
    }

    /** Number of new comments rejected at the hard backlog limit; accepted comments are never evicted. */
    fun rejectedCommentCount(): Long = synchronized(lock) { rejectedComments }

    fun close() {
        synchronized(lock) {
            commentQueue.clear()
            priorityQueue.clear()
            normalQueue.clear()
        }
        wake.close()
    }
}
