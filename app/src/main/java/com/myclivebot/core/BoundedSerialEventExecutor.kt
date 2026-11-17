package com.myclivebot.core

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Serial LIVE frame processor with a bounded backlog.
 *
 * When the pending queue is full, the submitting WebSocket callback waits for
 * capacity. This provides backpressure without dropping frames or running frame
 * decoding concurrently, so accepted frames retain their arrival order.
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
            if (pool.isShutdown) {
                throw RejectedExecutionException("LIVE event processor is closed")
            }
            try {
                // Block only the submitting callback while the bounded queue is full.
                pool.queue.put(task)
                // A concurrent disconnect may have shut down and drained the queue.
                if (pool.isShutdown && pool.queue.remove(task)) {
                    throw RejectedExecutionException("LIVE event processor closed while submitting")
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RejectedExecutionException("Interrupted while waiting for LIVE event capacity", interrupted)
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

    override fun close() {
        executor.shutdownNow()
    }
}
