package com.myclivebot.core

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedSerialEventExecutorTest {
    @Test fun rejectsImmediatelyWhenQueueIsFullWithoutReorderingAcceptedFrames() {
        val executor = BoundedSerialEventExecutor(queueCapacity = 1, threadName = "test-live-events")
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val order = Collections.synchronizedList(mutableListOf<Int>())

        try {
            assertTrue(executor.tryExecute(Runnable {
                firstStarted.countDown()
                try {
                    if (releaseFirst.await(2, TimeUnit.SECONDS)) order += 1
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

            // Saturation is reported immediately; the producer never waits for capacity.
            val startedAt = System.nanoTime()
            assertFalse(executor.tryExecute(Runnable { order += 3 }))
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue("Queue rejection must be non-blocking (elapsed=$elapsedMs ms)", elapsedMs < 100)
            assertEquals(1, executor.queuedCount())

            releaseFirst.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2), order)
            assertTrue(executor.queuedCount() <= 1)
        } finally {
            releaseFirst.countDown()
            executor.close()
        }
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
