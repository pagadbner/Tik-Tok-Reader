package com.myclivebot.core

import java.util.UUID

data class LiveComment(
    val userId: String,
    val displayName: String,
    val text: String,
    val avatarUrl: String? = null,
    val isModerator: Boolean = false,
    val isFollower: Boolean? = null,
    val receivedAt: Long = System.currentTimeMillis(),
    val id: String = UUID.randomUUID().toString(),
    val replyToDisplayName: String? = null,
    val contentLanguage: String? = null,
    val profileUsername: String = "",
    val replyToProfileUsername: String? = null
)
