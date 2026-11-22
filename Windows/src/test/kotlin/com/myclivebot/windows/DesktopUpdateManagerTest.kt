package com.myclivebot.windows

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopUpdateManagerTest {
    private fun assets(includeChecksum: Boolean = true) = JSONArray().apply {
        put(JSONObject()
            .put("name", "TikTokReader-Windows-Setup.exe")
            .put("browser_download_url", "https://example.invalid/TikTokReader-Windows-Setup.exe"))
        if (includeChecksum) {
            put(JSONObject()
                .put("name", "TikTokReader-Windows-Setup.exe.sha256")
                .put("browser_download_url", "https://example.invalid/TikTokReader-Windows-Setup.exe.sha256"))
        }
    }

    @Test
    fun detectsNewerWindowsReleaseFromSharedReleaseMetadata() {
        val update = parseDesktopUpdateInfo(
            "AndroidVersionName: 0.13.15\nWindowsVersionName: 0.13.17",
            assets(),
            "0.13.16"
        )
        assertEquals("0.13.17", update?.versionName)
        assertEquals("https://example.invalid/TikTokReader-Windows-Setup.exe", update?.installerUrl)
        assertEquals("https://example.invalid/TikTokReader-Windows-Setup.exe.sha256", update?.checksumUrl)
    }

    @Test
    fun ignoresSameOrOlderWindowsVersion() {
        assertNull(parseDesktopUpdateInfo("WindowsVersionName: 0.13.16", assets(), "0.13.16"))
        assertNull(parseDesktopUpdateInfo("WindowsVersionName: 0.13.15", assets(), "0.13.16"))
    }

    @Test
    fun requiresBothInstallerAndChecksumAssets() {
        assertNull(parseDesktopUpdateInfo("WindowsVersionName: 0.13.17", assets(false), "0.13.16"))
    }

    @Test
    fun ignoresAndroidOnlyReleaseMetadata() {
        assertNull(parseDesktopUpdateInfo("AndroidVersionName: 0.13.17", assets(), "0.13.16"))
    }

    @Test
    fun comparesNumericVersionPartsRatherThanLexicalText() {
        assertEquals(1, DesktopUpdateManager.compareVersions("0.13.10", "0.13.9"))
        assertEquals(-1, DesktopUpdateManager.compareVersions("0.13.9", "0.13.10"))
    }
}
