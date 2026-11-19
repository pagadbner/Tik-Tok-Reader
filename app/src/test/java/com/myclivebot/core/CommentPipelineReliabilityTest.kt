package com.myclivebot.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentPipelineReliabilityTest {
    private fun engine(blockFirst: CompletableDeferred<Unit>? = null) = object : SpeechEngine {
        private var first = true
        override suspend fun initialize() = Unit
        override suspend fun speak(text: String) {
            if (first) {
                first = false
                blockFirst?.await()
            }
        }
        override suspend fun stop() = Unit
    }

    @Test fun distinguishesFilteredAcceptedAndRetryOutcomes() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val queue = SpeechQueueWorker(engine(gate), scope, capacity = 5)
        val pipeline = CommentPipeline(
            CommentFilter(),
            queue,
            ReaderSettings(blockedWords = setOf("blocked"))
        )

        try {
            assertEquals(
                CommentHandlingResult.FILTERED,
                pipeline.onComment(LiveComment("filtered", "Filtered", "blocked word"))
            )
            assertEquals(
                CommentHandlingResult.ACCEPTED,
                pipeline.onComment(LiveComment("accepted-1", "One", "first comment"))
            )
            repeat(5) { index ->
                assertEquals(
                    CommentHandlingResult.ACCEPTED,
                    pipeline.onComment(LiveComment("accepted-$index", "User$index", "comment $index"))
                )
            }
            assertEquals(
                CommentHandlingResult.RETRY,
                pipeline.onComment(LiveComment("overflow", "Overflow", "must be retried"))
            )
            gate.complete(Unit)
        } finally {
            gate.complete(Unit)
            pipeline.close()
            queue.close()
            scope.cancel()
        }
    }

    @Test fun closedPipelineRejectsLateCommentsAndEvents() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val queue = SpeechQueueWorker(engine(), scope, capacity = 5)
        val pipeline = CommentPipeline(CommentFilter(), queue, ReaderSettings())
        pipeline.close()

        assertEquals(
            CommentHandlingResult.RETRY,
            pipeline.onComment(LiveComment("late", "Late", "too late"))
        )
        assertFalse(pipeline.onEvent(LiveEvent(LiveEventType.FOLLOW, "Late", userId = "late")))
        assertEquals(0, queue.queuedCount())
        queue.close()
        scope.cancel()
    }

    @Test fun followerBatchLimitDoesNotCountUntrackedDuplicateOverflowUsers() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val queue = SpeechQueueWorker(engine(), scope, capacity = 5)
        val pipeline = CommentPipeline(
            CommentFilter(),
            queue,
            ReaderSettings(readFollows = true),
            followBatchWindowMs = 60_000L
        )

        try {
            repeat(1_000) { index ->
                assertTrue(
                    pipeline.onEvent(
                        LiveEvent(LiveEventType.FOLLOW, "User$index", userId = "user-$index")
                    )
                )
            }
            assertFalse(
                pipeline.onEvent(LiveEvent(LiveEventType.FOLLOW, "Overflow", userId = "overflow-user"))
            )
            assertFalse(
                pipeline.onEvent(LiveEvent(LiveEventType.FOLLOW, "Overflow", userId = "overflow-user"))
            )
        } finally {
            pipeline.close()
            queue.close()
            scope.cancel()
        }
    }
}
