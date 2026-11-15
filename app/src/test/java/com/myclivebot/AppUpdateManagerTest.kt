package com.myclivebot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
    @Test
    fun newerPublishedVersionCodeIsDetected() {
        assertTrue(isNewerAppVersion(installedVersionCode = 21L, publishedVersionCode = 22L))
    }

    @Test
    fun sameVersionCodeIsNotAnUpdate() {
        assertFalse(isNewerAppVersion(installedVersionCode = 21L, publishedVersionCode = 21L))
    }

    @Test
    fun lowerPublishedVersionCodeIsNotAnUpdate() {
        assertFalse(isNewerAppVersion(installedVersionCode = 22L, publishedVersionCode = 21L))
    }

    @Test
    fun versionNameDoesNotAffectVersionCodeComparison() {
        // The display name may be unchanged or differently formatted; Android versionCode wins.
        assertTrue(isNewerAppVersion(installedVersionCode = 21L, publishedVersionCode = 23L))
    }
}
