package com.myclivebot.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentFilterTest {
    @Test fun blocksConfiguredWords() {
        val filter = CommentFilter()
        val settings = ReaderSettings(blockedWords = setOf("spoiler"))
        assertFalse(filter.shouldRead(LiveComment("1", "Ana", "hay un SPOILER"), settings))
    }

    @Test fun blocksIgnoredUsers() {
        val filter = CommentFilter()
        val settings = ReaderSettings(ignoredUsers = setOf("troll"))
        assertFalse(filter.shouldRead(LiveComment("troll", "Troll", "hola"), settings))
    }

    @Test fun suppressesRepeatedMessagesFromSameUserWithinWindow() {
        val filter = CommentFilter()
        val settings = ReaderSettings(suppressDuplicatesSeconds = 5)
        assertTrue(filter.shouldRead(LiveComment("1", "Ana", "hola", receivedAt = 10_000), settings))
        assertFalse(filter.shouldRead(LiveComment("1", "Ana", "hola", receivedAt = 12_000), settings))
        assertTrue(filter.shouldRead(LiveComment("1", "Ana", "hola", receivedAt = 16_000), settings))
    }
}
