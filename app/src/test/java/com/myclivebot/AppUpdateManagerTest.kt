package com.myclivebot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertTrue(isNewerAppVersion(installedVersionCode = 21L, publishedVersionCode = 23L))
    }

    @Test
    fun zeroAndNegativeOrEqualCodesAreNotNewer() {
        assertFalse(isNewerAppVersion(installedVersionCode = 0L, publishedVersionCode = 0L))
        assertFalse(isNewerAppVersion(installedVersionCode = 23L, publishedVersionCode = 0L))
    }

    @Test
    fun parsesPublishedVersionCodeFromReleaseNotes() {
        assertEquals(23L, parsePublishedVersionCode("VersionName: 0.13.6\nVersionCode: 23\n"))
    }

    @Test
    fun acceptsEqualsSeparatorAndWhitespace() {
        assertEquals(24L, parsePublishedVersionCode("  VersionCode = 24  "))
    }

    @Test
    fun rejectsMissingOrMalformedVersionCode() {
        assertNull(parsePublishedVersionCode("VersionName: 0.13.6"))
        assertNull(parsePublishedVersionCode("VersionCode: twenty-three"))
    }

    @Test
    fun rejectsOverflowingVersionCode() {
        assertNull(parsePublishedVersionCode("VersionCode: 999999999999999999999999"))
    }
}
