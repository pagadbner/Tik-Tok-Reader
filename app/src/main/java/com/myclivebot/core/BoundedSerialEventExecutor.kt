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
 * Submissions never wait for queue capacity. If the queue is full, the incoming
 * frame is rejected and is not acknowledged; previously accepted frames remain
 * in FIFO order. The task currently being processed is never interrupted.
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
     * Returns false when the executor is shut down or its queue is full.
     * Rejected frames are not acknowledged by enqueueAndAcknowledge; accepted
     * tasks remain queued in FIFO order.
     */
    fun tryExecute(task: Runnable): Boolean =
        try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            rejectedTasks.incrementAndGet()
            false
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
