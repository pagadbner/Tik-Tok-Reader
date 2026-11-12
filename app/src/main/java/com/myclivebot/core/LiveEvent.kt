package com.myclivebot.core

enum class LiveEventType { FOLLOW, GIFT }

data class LiveEvent(
    val type: LiveEventType,
    val displayName: String,
    val detail: String = "",
    val receivedAt: Long = System.currentTimeMillis()
)
