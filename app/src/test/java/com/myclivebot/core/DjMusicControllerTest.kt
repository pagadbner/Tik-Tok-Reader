package com.myclivebot.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DjMusicControllerTest {

    @Test
    fun livePlayRequestsRespectAllAccessModes() {
        val controller = DjMusicController

        // Moderator-only mode rejects viewers and followers, but accepts moderators.
        controller.setFollowersOnly(false)
        controller.setModeratorsOnly(true)
        assertEquals(false, controller.state.value.followersOnly)
        var count = controller.state.value.pendingSearches.size
        controller.handleLiveComment(
            LiveComment(userId = "viewer", displayName = "Viewer", text = "!play canción", isModerator = false, isFollower = true)
        )
        assertEquals(count, controller.state.value.pendingSearches.size)

        controller.handleLiveComment(
            LiveComment(userId = "mod", displayName = "Mod", text = "!play canción", isModerator = true, isFollower = true)
        )
        count++
        assertEquals(count, controller.state.value.pendingSearches.size)

        // Follower-only mode keeps its original behavior.
        controller.setFollowersOnly(true)
        assertEquals(false, controller.state.value.moderatorsOnly)
        controller.handleLiveComment(
            LiveComment(userId = "viewer", displayName = "Viewer", text = "!play otra", isModerator = false, isFollower = false)
        )
        assertEquals(count, controller.state.value.pendingSearches.size)

        controller.handleLiveComment(
            LiveComment(userId = "follower", displayName = "Follower", text = "!play otra", isModerator = false, isFollower = true)
        )
        count++
        assertEquals(count, controller.state.value.pendingSearches.size)

        // All mode accepts any viewer and clears the other restrictions.
        controller.setFollowersOnly(false)
        assertEquals(false, controller.state.value.moderatorsOnly)
        controller.handleLiveComment(
            LiveComment(userId = "viewer", displayName = "Viewer", text = "!play libre", isModerator = false, isFollower = false)
        )
        count++
        assertEquals(count, controller.state.value.pendingSearches.size)
    }
    @Test
    fun queueCanBeReorderedAndPlayedNowWithoutDroppingCurrentTrack() {
        val controller = DjMusicController
        controller.clearQueue()

        val current = DjMusicTrack("aaaaaaaaaaa", "Actual", "", requestedBy = "Tú")
        val second = DjMusicTrack("bbbbbbbbbbb", "Segunda", "", requestedBy = "Tú")
        val third = DjMusicTrack("ccccccccccc", "Tercera", "", requestedBy = "Tú")

        controller.addTrack(current)
        controller.addTrack(second)
        controller.addTrack(third)

        controller.moveQueueTrack(1, 0)
        assertEquals(listOf(third, second), controller.state.value.queue)

        controller.playQueueTrackNow(1)
        assertEquals(second, controller.state.value.currentTrack)
        assertEquals(listOf(current, third), controller.state.value.queue)

        controller.clearQueue()
    }

}
