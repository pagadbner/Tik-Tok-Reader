package com.myclivebot.core

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
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

    /** Non-blocking enqueue. A saturated or closed queue rejects the new frame immediately. */
    fun tryExecute(task: Runnable): Boolean {
        return try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
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
 * Enqueue without blocking the OkHttp reader. Acknowledge only after the
 * serial worker has finished processing the frame and explicitly permits it.
 * Returning false keeps a rejected comment frame eligible for server retry.
 */
internal fun enqueueAndAcknowledge(
    worker: BoundedSerialEventExecutor,
    processFrame: () -> Boolean,
    acknowledge: () -> Unit
): Boolean {
    return worker.tryExecute(Runnable {
        val processed = runCatching { processFrame() }.getOrDefault(false)
        if (processed) runCatching { acknowledge() }
    })
}
