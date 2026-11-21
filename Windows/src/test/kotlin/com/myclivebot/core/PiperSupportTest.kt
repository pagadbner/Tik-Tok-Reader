package com.myclivebot.core

import io.github.jvoiceproject.piperjni.PiperJNI
import kotlin.test.Test
import kotlin.test.assertTrue

class PiperSupportTest {
    @Test
    fun windowsNativePiperLibraryLoads() {
        PiperJNI.loadLibrary()
        assertTrue(PiperJNI.getPiperVersion().isNotBlank())
    }

    @Test
    fun voiceCatalogIdsAreUniqueAndDefaultExists() {
        val ids = PiperVoiceCatalog.voices.map { it.id }
        assertTrue(ids.size == ids.toSet().size)
        assertTrue(ids.contains(PiperVoiceCatalog.DEFAULT_VOICE_ID))
        assertTrue(PiperVoiceCatalog.find("unknown").id == PiperVoiceCatalog.DEFAULT_VOICE_ID)
    }

    @Test
    fun directVoiceEntriesHaveBothDownloadUrls() {
        PiperVoiceCatalog.voices.filter { it.directDownload }.forEach {
            assertTrue(!it.modelUrl.isNullOrBlank(), "Missing model URL: ${it.id}")
            assertTrue(!it.configUrl.isNullOrBlank(), "Missing config URL: ${it.id}")
        }
    }
}
