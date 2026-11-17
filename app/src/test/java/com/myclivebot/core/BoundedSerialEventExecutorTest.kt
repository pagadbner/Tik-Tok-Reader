package com.myclivebot.core

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedSerialEventExecutorTest {
    @Test fun appliesBackpressureWithoutDroppingOrReorderingFrames() {
        val executor = BoundedSerialEventExecutor(queueCapacity = 1, threadName = "test-live-events")
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val thirdSubmitted = CountDownLatch(1)
        val completed = CountDownLatch(3)
        val order = Collections.synchronizedList(mutableListOf<Int>())

        try {
            executor.execute(Runnable {
                firstStarted.countDown()
                try {
                    if (releaseFirst.await(2, TimeUnit.SECONDS)) order += 1
                } finally {
                    completed.countDown()
                }
            })
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

            executor.execute(Runnable {
                order += 2
                completed.countDown()
            })
            assertEquals(1, executor.queuedCount())

            val producer = thread(name = "test-live-producer") {
                executor.execute(Runnable {
                    order += 3
                    completed.countDown()
                })
                thirdSubmitted.countDown()
            }

            // The third frame must wait while the only pending slot is occupied.
            assertFalse(thirdSubmitted.await(100, TimeUnit.MILLISECONDS))
            assertTrue(executor.queuedCount() <= 1)

            releaseFirst.countDown()
            assertTrue(thirdSubmitted.await(2, TimeUnit.SECONDS))
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            producer.join(2_000)

            assertEquals(listOf(1, 2, 3), order)
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
