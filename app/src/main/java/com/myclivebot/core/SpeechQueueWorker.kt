package com.myclivebot.core

import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single speech consumer with two bounded lanes. Important LIVE events are spoken
 * before queued chat messages, but never interrupt audio that is already playing.
 */
class SpeechQueueWorker(
    private val engine: SpeechEngine,
    scope: CoroutineScope,
    capacity: Int = 30,
    private val priorityCapacity: Int = 16,
    private val onSpoken: (String) -> Unit = {}
) {
    private val lock = Any()
    private val normalQueue = ArrayDeque<String>()
    private val priorityQueue = ArrayDeque<String>()
    private val normalCapacity = capacity.coerceAtLeast(1)
    private val importantCapacity = priorityCapacity.coerceAtLeast(1)
    private val priorityBurstLimit = 3
    private var consecutivePriority = 0
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            while (isActive) {
                val next = synchronized(lock) {
                    when {
                        normalQueue.isNotEmpty() &&
                            (priorityQueue.isEmpty() || consecutivePriority >= priorityBurstLimit) -> {
                            consecutivePriority = 0
                            normalQueue.removeFirst()
                        }
                        priorityQueue.isNotEmpty() -> {
                            consecutivePriority++
                            priorityQueue.removeFirst()
                        }
                        normalQueue.isNotEmpty() -> {
                            consecutivePriority = 0
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
                    engine.speak(next)
                    onSpoken(next)
                } catch (_: Exception) {
                    // A failed utterance must not kill the worker or block later comments.
                }
            }
        }
    }

    /** Returns false when the selected bounded lane is full. */
    fun enqueue(text: String, priority: Boolean = false): Boolean {
        if (text.isBlank()) return false
        val accepted = synchronized(lock) {
            val queue = if (priority) priorityQueue else normalQueue
            val limit = if (priority) importantCapacity else normalCapacity
            if (priority) {
                if (queue.size >= limit) false else {
                    queue.addLast(text)
                    true
                }
            } else {
                // Prefer fresh LIVE chat over stale backlog: keep the utterance
                // currently being spoken, evict the oldest pending comment.
                if (queue.size >= limit) queue.removeFirst()
                queue.addLast(text)
                true
            }
        }
        if (accepted) wake.trySend(Unit)
        return accepted
    }

    /** Drops the next pending chat comment without interrupting the utterance in progress or priority alerts. */
    fun skipNextComment(): Boolean = synchronized(lock) {
        if (normalQueue.isEmpty()) false else {
            normalQueue.removeFirst()
            true
        }
    }

    /** Drops pending chat comments while preserving priority LIVE alerts. */
    fun clearPendingComments(): Int = synchronized(lock) {
        val removed = normalQueue.size
        normalQueue.clear()
        removed
    }

    fun queuedCount(): Int = synchronized(lock) {
        priorityQueue.size + normalQueue.size
    }

    fun close() {
        synchronized(lock) {
            normalQueue.clear()
            priorityQueue.clear()
        }
        wake.close()
    }
}
