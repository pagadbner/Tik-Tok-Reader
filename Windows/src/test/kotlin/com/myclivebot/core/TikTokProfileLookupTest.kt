package com.myclivebot.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TikTokProfileLookupTest {
    @Test
    fun parsesLiveStatusWithoutTreatingUnknownResponsesAsOffline() {
        val live = """{"statusCode":0,"data":{"user":{"roomId":"123"},"liveRoom":{"status":2}}}"""
        val offline = """{"statusCode":0,"data":{"user":{"roomId":"0"},"liveRoom":{"status":4}}}"""
        val unknown = """{"statusCode":10001,"data":{}}"""
        assertEquals(true, TikTokProfileLookup.parseLiveStatus(live))
        assertEquals(false, TikTokProfileLookup.parseLiveStatus(offline))
        assertNull(TikTokProfileLookup.parseLiveStatus(unknown))
    }

    @Test
    fun onlyAcceptsHttpsAvatarUrlsFromTikTokCdnHosts() {
        val safe = """{"avatarLarger":"https:\/\/p16-sign.tiktokcdn-us.com\/obj\/avatar.jpeg"}"""
        val unsafe = """{"avatarLarger":"https:\/\/evil.example\/avatar.jpeg"}"""
        assertTrue(TikTokProfileLookup.extractAvatarUrl(safe)!!.startsWith("https://p16-sign.tiktokcdn-us.com/"))
        assertNull(TikTokProfileLookup.extractAvatarUrl(unsafe))
        assertFalse(TikTokProfileLookup.extractAvatarUrl("""{"avatarLarger":"http:\/\/tiktokcdn.com\/a"}""") != null)
    }
}
