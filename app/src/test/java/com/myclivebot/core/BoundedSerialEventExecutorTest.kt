package com.myclivebot.core

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedSerialEventExecutorTest {
    @Test fun preservesAcceptedFramesAndRejectsIncomingFrameWhenQueueIsFull() {
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

            // A saturated queue preserves the accepted frame and rejects the new one.
            val startedAt = System.nanoTime()
            assertFalse(executor.tryExecute(Runnable {
                order += 3
                completed.countDown()
            }))
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue("Receive callback must not block on a saturated queue (elapsed=$elapsedMs ms)", elapsedMs < 250)
            assertEquals(1, executor.queuedCount())
            assertEquals(1L, executor.droppedTaskCount())

            releaseFirst.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2), order)
            assertTrue(executor.queuedCount() <= 1)
        } finally {
            releaseFirst.countDown()
            executor.close()
        }
    }

    @Test fun acknowledgesOnlyAfterFrameProcessingAcceptsTheComment() {
        val executor = BoundedSerialEventExecutor(queueCapacity = 1, threadName = "test-live-ack")
        val processingStarted = CountDownLatch(1)
        val releaseProcessing = CountDownLatch(1)
        val acknowledged = CountDownLatch(1)
        val ackCount = java.util.concurrent.atomic.AtomicInteger()

        try {
            assertTrue(enqueueAndAcknowledge(executor, {
                processingStarted.countDown()
                releaseProcessing.await(2, TimeUnit.SECONDS)
                true
            }) {
                ackCount.incrementAndGet()
                acknowledged.countDown()
            })
            assertTrue(processingStarted.await(2, TimeUnit.SECONDS))
            assertEquals(0, ackCount.get())
            releaseProcessing.countDown()
            assertTrue(acknowledged.await(2, TimeUnit.SECONDS))
            assertEquals(1, ackCount.get())
        } finally {
            releaseProcessing.countDown()
            executor.close()
        }
    }

    @Test fun doesNotAcknowledgeACommentRejectedByTheSpeechQueue() {
        val executor = BoundedSerialEventExecutor(queueCapacity = 1, threadName = "test-live-retry")
        val processed = CountDownLatch(1)
        val ackCount = java.util.concurrent.atomic.AtomicInteger()

        try {
            assertTrue(enqueueAndAcknowledge(executor, {
                processed.countDown()
                false
            }) {
                ackCount.incrementAndGet()
            })
            assertTrue(processed.await(2, TimeUnit.SECONDS))
            Thread.sleep(30)
            assertEquals(0, ackCount.get())
        } finally {
            executor.close()
        }
    }

    @Test fun doesNotAcknowledgeWhenWorkerHasAlreadyClosed() {
        val executor = BoundedSerialEventExecutor(queueCapacity = 1, threadName = "test-live-closed")
        val ackCount = java.util.concurrent.atomic.AtomicInteger()
        executor.close()

        assertFalse(enqueueAndAcknowledge(executor, { true }) {
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
    @Test fun rejectsSaturatedFrameImmediatelyWithoutEvictingAcceptedFrames() {
        val executor = BoundedSerialEventExecutor(queueCapacity = 1, threadName = "test-live-nonblocking")
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val order = Collections.synchronizedList(mutableListOf<Int>())

        try {
            assertTrue(executor.tryExecute(Runnable {
                firstStarted.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
                order += 1
                completed.countDown()
            }))
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            assertTrue(executor.tryExecute(Runnable {
                order += 2
                completed.countDown()
            }))

            val startedAt = System.nanoTime()
            assertFalse(executor.tryExecute(Runnable { order += 3 }))
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue("Saturated receive must return immediately (elapsed=$elapsedMs ms)", elapsedMs < 250)
            assertEquals(1, executor.queuedCount())

            releaseFirst.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2), order)
            assertEquals(1L, executor.droppedTaskCount())
        } finally {
            releaseFirst.countDown()
            executor.close()
        }
    }

}
