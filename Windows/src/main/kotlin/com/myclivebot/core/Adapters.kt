package com.myclivebot.core

/** Contract for the local speech engine used by the FIFO reader. */
interface SpeechEngine {
    suspend fun initialize()
    suspend fun speak(text: String)
    suspend fun stop()
}
