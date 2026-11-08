package com.myclivebot.core

data class LiveComment(
    val userId: String,
    val displayName: String,
    val text: String,
    val isModerator: Boolean = false,
    val receivedAt: Long = System.currentTimeMillis()
)
