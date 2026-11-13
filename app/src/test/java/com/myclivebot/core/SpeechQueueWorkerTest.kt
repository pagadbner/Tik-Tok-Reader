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
}
