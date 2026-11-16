package com.myclivebot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class TikTokProfileLookupTest {
    @Test
    fun extractsPublicAvatarUrlAndDecodesEscapedSlashes() {
        val html = """{"user":{"avatarLarger":"https:\\u002F\\u002Fp16-sign.tiktokcdn-us.com\\u002Ftos-avt.jpeg"}}"""
        assertEquals(
            "https://p16-sign.tiktokcdn-us.com/tos-avt.jpeg",
            TikTokProfileLookup.extractAvatarUrl(html)
        )
    }

    @Test
    fun rejectsNonHttpsOrUntrustedImageHosts() {
        assertNull(TikTokProfileLookup.extractAvatarUrl("""{"avatarLarger":"http://example.com/avatar.jpg"}"""))
        assertNull(TikTokProfileLookup.extractAvatarUrl("""{"avatarLarger":"https://example.com/avatar.jpg"}"""))
    }

    @Test
    fun returnsNullWhenProfileHtmlDoesNotContainAvatar() {
        assertNull(TikTokProfileLookup.extractAvatarUrl("<html>profile unavailable</html>"))
    }
    @Test
    fun detectsActiveLiveRoom() {
        val body = """{"statusCode":0,"data":{"user":{"roomId":"12345"},"liveRoom":{"status":2}}}"""
        assertTrue(TikTokProfileLookup.parseLiveStatus(body) == true)
    }

    @Test
    fun detectsProfileThatIsNotLive() {
        val body = """{"statusCode":0,"data":{"user":{"roomId":"0"},"liveRoom":{"status":4}}}"""
        assertFalse(TikTokProfileLookup.parseLiveStatus(body) == true)
    }

    @Test
    fun returnsUnknownWhenLiveStatusResponseIsInvalid() {
        assertNull(TikTokProfileLookup.parseLiveStatus("not-json"))
        assertNull(TikTokProfileLookup.parseLiveStatus("""{"statusCode":1}"""))
    }

}
