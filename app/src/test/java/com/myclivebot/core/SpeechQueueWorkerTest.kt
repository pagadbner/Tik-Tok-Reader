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
        val worker = SpeechQueueWorker(engine, scope, capacity = 1)

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
        val worker = SpeechQueueWorker(engine, scope, capacity = 2)

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
}
