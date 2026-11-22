package com.myclivebot.core

enum class LiveEventType { FOLLOW, GIFT, SHARE, JOIN, LIKE }

data class LiveEvent(
    val type: LiveEventType,
    val displayName: String,
    val detail: String = "",
    val receivedAt: Long = System.currentTimeMillis(),
    val userId: String = "",
    val isFollower: Boolean? = null,
    val giftGroupId: String = ""
)
