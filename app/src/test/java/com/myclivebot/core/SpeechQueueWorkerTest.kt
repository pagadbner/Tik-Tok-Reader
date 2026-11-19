package com.myclivebot.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechQueueWorkerTest {
    @Test fun skipsOnlyTheSelectedPendingChatComment() {
        val gate = CompletableDeferred<Unit>()
        val spoken = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) {
                spoken += text
                if (text == "currently speaking") gate.await()
            }
            override suspend fun stop() = Unit
        }
        val worker = SpeechQueueWorker(engine, scope, capacity = 4)

        try {
            assertTrue(worker.enqueue("currently speaking"))
            assertTrue(worker.enqueue("skip this", itemId = "comment-2"))
            assertTrue(worker.enqueue("keep this", itemId = "comment-3"))
            assertTrue(worker.skipComment("comment-2"))
            assertEquals(1, worker.queuedCount())
            gate.complete(Unit)
            assertEquals(listOf("currently speaking", "keep this"), spoken)
        } finally {
            worker.close()
            scope.cancel()
        }
    }

    @Test fun priorityAlertsDoNotStarvePendingChat() {
        val gate = CompletableDeferred<Unit>()
        val spoken = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) {
                spoken += text
                if (text == "currently speaking") gate.await()
            }
            override suspend fun stop() = Unit
        }
        val worker = SpeechQueueWorker(engine, scope, capacity = 4, priorityCapacity = 8)

        try {
            assertTrue(worker.enqueue("currently speaking"))
            repeat(5) { assertTrue(worker.enqueue("alert ${it + 1}", priority = true)) }
            assertTrue(worker.enqueue("pending chat"))
            gate.complete(Unit)
            assertEquals(
                listOf("currently speaking", "alert 1", "alert 2", "alert 3", "pending chat", "alert 4", "alert 5"),
                spoken
            )
        } finally {
            worker.close()
            scope.cancel()
        }
    }

    @Test fun liveCommentsAreSpokenBeforeQueuedEventAlerts() {
        val gate = CompletableDeferred<Unit>()
        val spoken = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) {
                spoken += text
                if (text == "currently speaking") gate.await()
            }
            override suspend fun stop() = Unit
        }
        val worker = SpeechQueueWorker(engine, scope, capacity = 4, priorityCapacity = 4)

        try {
            assertTrue(worker.enqueue("currently speaking"))
            assertTrue(worker.enqueue("follow batch", priority = true))
            assertTrue(worker.enqueue("new LIVE comment", comment = true, itemId = "comment-1"))
            gate.complete(Unit)
            assertEquals(
                listOf("currently speaking", "new LIVE comment", "follow batch"),
                spoken
            )
        } finally {
            worker.close()
            scope.cancel()
        }
    }

    @Test fun rejectsOverflowWithoutEvictingAcceptedChatComments() {
        val gate = CompletableDeferred<Unit>()
        val spoken = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) {
                spoken += text
                if (text == "currently speaking") gate.await()
            }
            override suspend fun stop() = Unit
        }
        val worker = SpeechQueueWorker(engine, scope, capacity = 20)

        try {
            assertTrue(worker.enqueue("currently speaking"))
            repeat(20) { index ->
                assertTrue(worker.enqueue("comment-${index + 1}", comment = true, itemId = "c-${index + 1}"))
            }
            assertEquals(20, worker.queuedCount())
            assertTrue(!worker.enqueue("overflow", comment = true, itemId = "overflow"))
            assertEquals(1L, worker.rejectedCommentCount())
            assertEquals(20, worker.queuedCount())

            gate.complete(Unit)
            assertEquals(
                listOf("currently speaking") + (1..20).map { "comment-$it" },
                spoken
            )
        } finally {
            worker.close()
            scope.cancel()
        }
    }

    @Test fun eventAlertsGetAChanceAfterTenConsecutiveComments() {
        val gate = CompletableDeferred<Unit>()
        val spoken = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) {
                spoken += text
                if (text == "currently speaking") gate.await()
            }
            override suspend fun stop() = Unit
        }
        val worker = SpeechQueueWorker(engine, scope, capacity = 11)

        try {
            assertTrue(worker.enqueue("currently speaking"))
            repeat(11) { assertTrue(worker.enqueue("chat-${it + 1}", comment = true)) }
            assertTrue(worker.enqueue("follow alert", priority = true))
            gate.complete(Unit)
            assertEquals(
                listOf("currently speaking") +
                    (1..10).map { "chat-$it" } +
                    listOf("follow alert", "chat-11"),
                spoken
            )
        } finally {
            worker.close()
            scope.cancel()
        }
    }
    
    @Test fun rejectsNewCommentsAfterQueueClose() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) = Unit
            override suspend fun stop() = Unit
        }
        val worker = SpeechQueueWorker(engine, scope, capacity = 5)
        worker.close()

        assertTrue(!worker.enqueue("late comment", comment = true, itemId = "late"))
        assertTrue(!worker.enqueueComments(listOf("late welcome" to null, "late comment" to "late")))
        assertEquals(0, worker.queuedCount())
        scope.cancel()
    }

    @Test fun manualClearResetsOverflowWarningForTheNextSaturationEpisode() {
        val gate = CompletableDeferred<Unit>()
        val warningCounts = mutableListOf<Long>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) {
                if (text == "currently speaking") gate.await()
            }
            override suspend fun stop() = Unit
        }
        val worker = SpeechQueueWorker(
            engine,
            scope,
            capacity = 5,
            onCommentQueueFull = { warningCounts += it }
        )

        try {
            assertTrue(worker.enqueue("currently speaking"))
            repeat(5) { assertTrue(worker.enqueue("first-$it", comment = true)) }
            assertTrue(!worker.enqueue("overflow-1", comment = true))
            assertEquals(1, warningCounts.size)
            assertEquals(5, worker.clearPendingComments())

            repeat(5) { assertTrue(worker.enqueue("second-$it", comment = true)) }
            assertTrue(!worker.enqueue("overflow-2", comment = true))
            assertEquals(2, warningCounts.size)
            gate.complete(Unit)
        } finally {
            gate.complete(Unit)
            worker.close()
            scope.cancel()
        }
    }

    @Test fun skipAlsoResetsOverflowWarningAfterQueueDrainsBelowHalf() {
        val gate = CompletableDeferred<Unit>()
        val warnings = mutableListOf<Long>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) {
                if (text == "currently speaking") gate.await()
            }
            override suspend fun stop() = Unit
        }
        val worker = SpeechQueueWorker(engine, scope, capacity = 5, onCommentQueueFull = { warnings += it })

        try {
            assertTrue(worker.enqueue("currently speaking"))
            repeat(5) { index -> assertTrue(worker.enqueue("skip-$index", comment = true, itemId = "skip-$index")) }
            assertTrue(!worker.enqueue("overflow-1", comment = true))
            assertTrue(worker.skipComment("skip-0"))
            assertTrue(worker.skipComment("skip-1"))
            assertTrue(worker.skipComment("skip-2"))
            assertTrue(worker.skipComment("skip-3"))
            assertTrue(worker.enqueue("after-skip-1", comment = true))
            assertTrue(worker.enqueue("after-skip-2", comment = true))
            assertTrue(!worker.enqueue("overflow-2", comment = true))
            assertEquals(2, warnings.size)
            gate.complete(Unit)
        } finally {
            gate.complete(Unit)
            worker.close()
            scope.cancel()
        }
    }

}
