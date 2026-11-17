package com.myclivebot.core

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Serial event processor with a bounded backlog.
 *
 * When the backlog is full, the submitting thread waits for capacity instead of
 * allocating unbounded memory or silently dropping a LIVE frame. Since WebSocket
 * callbacks submit frames in arrival order, the single worker preserves that order.
 * Closing the connector may discard pending work intentionally.
 */
internal class BoundedSerialEventExecutor(
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    threadName: String = "TikLBot-LIVE-Events"
) : Closeable {
    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 256
    }

    private val executor: ThreadPoolExecutor

    init {
        require(queueCapacity > 0) { "queueCapacity must be positive" }
        val threadFactory = ThreadFactory { runnable ->
            Thread(runnable, threadName).apply { isDaemon = true }
        }
        val rejectionHandler = RejectedExecutionHandler { task, pool ->
            if (!pool.isShutdown) {
                try {
                    // Apply backpressure only when the bounded queue is saturated.
                    pool.queue.put(task)
                    // A concurrent disconnect is allowed to cancel pending frames.
                    if (pool.isShutdown) pool.queue.remove(task)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity),
            threadFactory,
            rejectionHandler
        )
    }

    fun execute(task: Runnable) {
        executor.execute(task)
    }

    fun queuedCount(): Int = executor.queue.size

    fun isShutdown(): Boolean = executor.isShutdown

    override fun close() {
        executor.shutdownNow()
    }
}
