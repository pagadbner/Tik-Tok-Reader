package com.myclivebot.core

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedSerialEventExecutorTest {
    @Test fun keepsNewestFramesWhenQueueIsFullWithoutBlockingOrReorderingRemainingFrames() {
        val executor = BoundedSerialEventExecutor(queueCapacity = 1, threadName = "test-live-events")
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val order = Collections.synchronizedList(mutableListOf<Int>())

        try {
            assertTrue(executor.tryExecute(Runnable {
                firstStarted.countDown()
                try {
                    releaseFirst.await(2, TimeUnit.SECONDS)
                    order += 1
                } finally {
                    completed.countDown()
                }
            }))
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

            assertTrue(executor.tryExecute(Runnable {
                order += 2
                completed.countDown()
            }))
            assertEquals(1, executor.queuedCount())

            // A saturated queue evicts its oldest waiting task and accepts the new frame.
            val startedAt = System.nanoTime()
            assertTrue(executor.tryExecute(Runnable {
                order += 3
                completed.countDown()
            }))
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue("Queue submission must be non-blocking (elapsed=$elapsedMs ms)", elapsedMs < 100)
            assertEquals(1, executor.queuedCount())
            assertEquals(1L, executor.droppedTaskCount())

            releaseFirst.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1, 3), order)
            assertTrue(executor.queuedCount() <= 1)
        } finally {
            releaseFirst.countDown()
            executor.close()
        }
    }

    @Test fun acknowledgesOnlyAfterFrameWasAcceptedByTheWorker() {
        val executor = BoundedSerialEventExecutor(queueCapacity = 1, threadName = "test-live-ack")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ackCount = java.util.concurrent.atomic.AtomicInteger()

        try {
            assertTrue(executor.tryExecute(Runnable {
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
            }))
            assertTrue(started.await(2, TimeUnit.SECONDS))

            assertTrue(enqueueAndAcknowledge(executor, Runnable { }) {
                ackCount.incrementAndGet()
            })
            assertEquals(1, ackCount.get())
            // Queue overflow still accepts the latest frame, then acknowledges it.
            assertTrue(enqueueAndAcknowledge(executor, Runnable { }) {
                ackCount.incrementAndGet()
            })
            assertEquals(2, ackCount.get())
            assertEquals(1L, executor.droppedTaskCount())
        } finally {
            release.countDown()
            executor.close()
        }
    }

    @Test fun doesNotAcknowledgeWhenWorkerHasAlreadyClosed() {
        val executor = BoundedSerialEventExecutor(queueCapacity = 1, threadName = "test-live-closed")
        val ackCount = java.util.concurrent.atomic.AtomicInteger()
        executor.close()

        assertFalse(enqueueAndAcknowledge(executor, Runnable { }) {
            ackCount.incrementAndGet()
        })
        assertEquals(0, ackCount.get())
    }

    @Test fun rejectsInvalidQueueCapacity() {
        try {
            BoundedSerialEventExecutor(queueCapacity = 0)
            throw AssertionError("Expected invalid capacity to fail")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
