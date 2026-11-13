package com.myclivebot.core

/** Decodes the public Webcast FollowInfo.follow_status enum without guessing missing values. */
object FollowerStatusParser {
    fun isFollowing(followStatus: Long?): Boolean? = followStatus?.let { it > 0L }
}
