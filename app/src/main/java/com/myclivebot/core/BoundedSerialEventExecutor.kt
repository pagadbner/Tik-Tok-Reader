package com.myclivebot.core

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Serial LIVE frame processor with a bounded backlog.
 *
 * Submission never waits for queue capacity: when full, tryExecute returns false
 * so the WebSocket reader can close the overloaded session without blocking
 * OkHttp's frame reader, ping/pong handling, or heartbeat traffic.
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
        executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity),
            threadFactory,
            ThreadPoolExecutor.AbortPolicy()
        )
    }

    /**
     * Returns false immediately if the bounded queue is full or the worker is closed.
     * Accepted tasks remain serial and FIFO.
     */
    fun tryExecute(task: Runnable): Boolean =
        try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            false
        }

    fun queuedCount(): Int = executor.queue.size

    override fun close() {
        executor.shutdownNow()
    }
}
