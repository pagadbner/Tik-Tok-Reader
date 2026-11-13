package com.myclivebot.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FollowerStatusParserTest {
    @Test fun decodesFollowInfoStatuses() {
        assertEquals(false, FollowerStatusParser.isFollowing(0L))
        assertEquals(true, FollowerStatusParser.isFollowing(1L))
        assertEquals(true, FollowerStatusParser.isFollowing(2L))
    }

    @Test fun missingFollowInfoRemainsUnknown() {
        assertEquals(null, FollowerStatusParser.isFollowing(null))
    }
}
