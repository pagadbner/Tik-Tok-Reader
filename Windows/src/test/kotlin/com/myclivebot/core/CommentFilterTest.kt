package com.myclivebot.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommentFilterTest {
    private val filter = CommentFilter()

    @Test
    fun profileUsernameKeepsUnderscores() {
        val settings = ReaderSettings(onlyUsername = true, useProfileUsername = true)
        val comment = LiveComment(
            userId = "user-1",
            displayName = "Karina",
            profileUsername = "karina_fu82",
            text = "Hola"
        )
        assertTrue(filter.shouldRead(comment, settings))
        assertEquals("karina_fu82", filter.toSpeech(comment, settings))
    }

    @Test
    fun blockedWordsUseWholeWordBoundaries() {
        val settings = ReaderSettings(blockedWords = setOf("spam"))
        val exactWord = LiveComment(userId = "1", displayName = "Ana", text = "esto es spam")
        val partOfWord = LiveComment(userId = "2", displayName = "Luis", text = "spammer")
        assertFalse(filter.shouldRead(exactWord, settings))
        assertTrue(filter.shouldRead(partOfWord, settings))
    }

    @Test
    fun followerOnlyFilterRejectsUnknownFollowerState() {
        val settings = ReaderSettings(followerOnlyChat = true)
        val comment = LiveComment(userId = "1", displayName = "Ana", text = "Hola", isFollower = null)
        assertFalse(filter.shouldRead(comment, settings))
    }
}
