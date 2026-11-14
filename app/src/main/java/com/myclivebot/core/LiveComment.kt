package com.myclivebot.core

data class LiveComment(
    val userId: String,
    val displayName: String,
    val text: String,
    val avatarUrl: String? = null,
    val isModerator: Boolean = false,
    val isFollower: Boolean? = null,
    val receivedAt: Long = System.currentTimeMillis()
)
