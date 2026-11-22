package com.myclivebot.windows

import java.net.HttpCookie
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopTtwidCookieProviderTest {
    @Test
    fun extractsOnlyValidatedTtwidCookie() {
        val cookies = listOf(
            HttpCookie("sessionid", "other-cookie-value"),
            HttpCookie("ttwid", "valid-ttwid-cookie-value")
        )
        assertEquals("valid-ttwid-cookie-value", DesktopTtwidCookieProvider.findTtwidCookie(cookies))
    }

    @Test
    fun ignoresMalformedTtwidCookie() {
        assertNull(DesktopTtwidCookieProvider.findTtwidCookie(listOf(HttpCookie("ttwid", "bad value"))))
    }

    @Test
    fun ignoresExpiredTtwidCookie() {
        val expired = HttpCookie("ttwid", "valid-ttwid-cookie-value").apply { maxAge = 0 }
        assertNull(DesktopTtwidCookieProvider.findTtwidCookie(listOf(expired)))
    }
}
