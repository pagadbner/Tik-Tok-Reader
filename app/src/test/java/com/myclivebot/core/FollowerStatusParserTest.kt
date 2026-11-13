package com.myclivebot.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FollowerStatusParserTest {
    @Test fun explicitIsFollowerFlagTakesPrecedence() {
        assertEquals(true, FollowerStatusParser.isFollower(true, 0L))
        assertEquals(false, FollowerStatusParser.isFollower(false, 1L))
    }

    @Test fun followStatusFallbackRecognizesFollowingAndMutual() {
        assertEquals(false, FollowerStatusParser.isFollower(null, 0L))
        assertEquals(true, FollowerStatusParser.isFollower(null, 1L))
        assertEquals(true, FollowerStatusParser.isFollower(null, 2L))
    }

    @Test fun missingMetadataRemainsUnknown() {
        assertEquals(null, FollowerStatusParser.isFollower(null, null))
    }
}
