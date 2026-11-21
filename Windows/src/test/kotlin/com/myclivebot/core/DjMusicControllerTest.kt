package com.myclivebot.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DjMusicControllerTest {
    @Test
    fun moderatorOnlyRequestsRejectRegularViewers() {
        val marker = "dj-test-moderator-${System.nanoTime()}"
        DjMusicController.setModeratorsOnly(true)
        val regular = LiveComment(
            userId = "viewer-${System.nanoTime()}",
            displayName = "Viewer",
            text = "!play ${marker}",
            isModerator = false
        )
        val before = DjMusicController.state.value.pendingSearches.size

        DjMusicController.handleLiveComment(regular)

        assertEquals(before, DjMusicController.state.value.pendingSearches.size)
        assertFalse(DjMusicController.state.value.pendingSearches.any { it.first == marker })
        DjMusicController.setModeratorsOnly(false)
    }

    @Test
    fun moderatorCanAddRequestWhenModeratorOnlyIsEnabled() {
        val marker = "dj-test-allowed-${System.nanoTime()}"
        DjMusicController.setModeratorsOnly(true)
        val moderator = LiveComment(
            userId = "moderator-${System.nanoTime()}",
            displayName = "Moderator",
            text = "!play ${marker}",
            isModerator = true
        )

        DjMusicController.handleLiveComment(moderator)

        assertTrue(DjMusicController.state.value.pendingSearches.any {
            it.first == marker && it.second == "Moderator"
        })
        DjMusicController.setModeratorsOnly(false)
    }

    @Test
    fun followerOnlyRequestsRejectUnknownFollowerStatus() {
        val marker = "dj-test-follower-${System.nanoTime()}"
        DjMusicController.setFollowersOnly(true)
        val unknownFollower = LiveComment(
            userId = "unknown-${System.nanoTime()}",
            displayName = "Unknown",
            text = "!play ${marker}",
            isFollower = null
        )
        val before = DjMusicController.state.value.pendingSearches.size

        DjMusicController.handleLiveComment(unknownFollower)

        assertEquals(before, DjMusicController.state.value.pendingSearches.size)
        assertFalse(DjMusicController.state.value.pendingSearches.any { it.first == marker })
        DjMusicController.setFollowersOnly(false)
    }

    @Test
    fun requestQueueProcessesRequestsInFifoOrder() {
        val first = "dj-test-first-${System.nanoTime()}"
        val second = "dj-test-second-${System.nanoTime()}"
        DjMusicController.enqueueSearch(first, "Tester 1")
        DjMusicController.enqueueSearch(second, "Tester 2")

        val next = DjMusicController.takeNextSearch()

        assertEquals(first to "Tester 1", next)
        DjMusicController.completeSearch(first, "Tester 1")
        assertEquals(second to "Tester 2", DjMusicController.takeNextSearch())
        DjMusicController.completeSearch(second, "Tester 2")
    }
}
