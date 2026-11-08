package com.myclivebot.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** A single consumer guarantees strict FIFO speech: a new comment never interrupts the current one. */
class SpeechQueueWorker(
    private val engine: SpeechEngine,
    scope: CoroutineScope,
    capacity: Int = 30,
    private val onSpoken: (String) -> Unit = {}
) {
    private val channel = Channel<String>(capacity.coerceAtLeast(1))

    init {
        scope.launch {
            for (text in channel) {
                try {
                    engine.speak(text)
                    onSpoken(text)
                } catch (_: Exception) {
                    // A failed utterance must not kill the worker or block later comments.
                }
            }
        }
    }

    /** Returns false when the bounded queue is full. */
    fun enqueue(text: String): Boolean = text.isNotBlank() && channel.trySend(text).isSuccess
    fun close() = channel.close()
}
