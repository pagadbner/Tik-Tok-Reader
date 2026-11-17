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
}
