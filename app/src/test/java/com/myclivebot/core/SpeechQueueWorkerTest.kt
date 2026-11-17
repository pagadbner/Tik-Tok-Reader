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

    @Test fun dropsOldestPendingChatToKeepQueueFresh() {
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
            assertTrue(worker.enqueue("older pending"))
            assertTrue(worker.enqueue("newer pending"))
            assertTrue(worker.enqueue("latest comment"))
            assertEquals(2, worker.queuedCount())

            gate.complete(Unit)
            assertEquals(
                listOf("currently speaking", "newer pending", "latest comment"),
                spoken
            )
        } finally {
            worker.close()
            scope.cancel()
        }
    }
    @Test fun priorityQueueRejectsOverflowWithoutDuplicatingAcceptedAlerts() {
        val gate = CompletableDeferred<Unit>()
        val spoken = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) {
                spoken += text
                if (text == "playing") gate.await()
            }
            override suspend fun stop() = Unit
        }
        val worker = SpeechQueueWorker(engine, scope, capacity = 2, priorityCapacity = 2)

        try {
            assertTrue(worker.enqueue("playing", priority = true))
            assertTrue(worker.enqueue("alert 1", priority = true))
            assertTrue(worker.enqueue("alert 2", priority = true))
            assertEquals(false, worker.enqueue("alert 3", priority = true))
            assertEquals(2, worker.queuedCount())

            gate.complete(Unit)
            assertEquals(listOf("playing", "alert 1", "alert 2"), spoken)
        } finally {
            worker.close()
            scope.cancel()
        }
    }

    @Test fun failedSpeechDoesNotPreventFollowingLiveEvents() {
        val spoken = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) {
                spoken += text
                if (text == "broken alert") throw IllegalStateException("synthetic TTS failure")
            }
            override suspend fun stop() = Unit
        }
        val worker = SpeechQueueWorker(engine, scope, priorityCapacity = 4)

        try {
            assertTrue(worker.enqueue("broken alert", priority = true))
            assertTrue(worker.enqueue("next alert", priority = true))
            assertEquals(listOf("broken alert", "next alert"), spoken)
        } finally {
            worker.close()
            scope.cancel()
        }
    }

}
