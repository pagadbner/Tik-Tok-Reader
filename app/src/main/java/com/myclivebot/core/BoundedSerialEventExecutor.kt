package com.myclivebot.core

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Serial LIVE frame processor with a bounded FIFO backlog.
 *
 * Submissions are normally non-blocking. Under saturation, the caller waits
 * briefly for a free slot rather than evicting an already accepted frame. If
 * capacity still does not become available, the new frame is rejected and is
 * not acknowledged. Accepted frames remain in FIFO order.
 */
internal class BoundedSerialEventExecutor(
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    threadName: String = "TikLBot-LIVE-Events"
) : Closeable {
    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 1_024
    }

    private val rejectedTasks = AtomicLong()
    private val executor: ThreadPoolExecutor

    init {
        require(queueCapacity > 0) { "queueCapacity must be positive" }
        val queue = ArrayBlockingQueue<Runnable>(queueCapacity)
        val threadFactory = ThreadFactory { runnable ->
            Thread(runnable, threadName).apply { isDaemon = true }
        }
        executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            queue,
            threadFactory,
            ThreadPoolExecutor.AbortPolicy()
        )
    }

    /**
     * Enqueues without waiting in the normal case. If full, briefly waits for
     * a slot so accepted frames are not evicted and the WebSocket can be ACKed.
     */
    fun tryExecute(task: Runnable): Boolean {
        try {
            executor.execute(task)
            return true
        } catch (_: RejectedExecutionException) {
            if (executor.isShutdown) {
                rejectedTasks.incrementAndGet()
                return false
            }
        }

        return try {
            val accepted = executor.queue.offer(task, 500, TimeUnit.MILLISECONDS)
            if (!accepted || executor.isShutdown) {
                if (accepted) executor.remove(task)
                rejectedTasks.incrementAndGet()
                false
            } else {
                true
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            rejectedTasks.incrementAndGet()
            false
        }
    }

    fun queuedCount(): Int = executor.queue.size

    /** Number of incoming frames rejected because the queue was full or closed. */
    fun droppedTaskCount(): Long = rejectedTasks.get()

    override fun close() {
        executor.shutdownNow()
    }

}

/**
 * Enqueue first and acknowledge only after the bounded processor accepted the
 * frame. The acknowledgement callback runs on the caller (OkHttp reader) thread.
 */
internal fun enqueueAndAcknowledge(
    worker: BoundedSerialEventExecutor,
    task: Runnable,
    acknowledge: () -> Unit
): Boolean {
    val accepted = worker.tryExecute(task)
    if (accepted) acknowledge()
    return accepted
}
