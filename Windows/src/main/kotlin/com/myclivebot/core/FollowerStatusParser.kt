package com.myclivebot.core

/** Resolves follower membership from explicit Webcast is_follower, then follow_status metadata. */
object FollowerStatusParser {
    fun isFollower(isFollowerFlag: Boolean?, followStatus: Long?): Boolean? =
        isFollowerFlag ?: followStatus?.let { it > 0L }
}
