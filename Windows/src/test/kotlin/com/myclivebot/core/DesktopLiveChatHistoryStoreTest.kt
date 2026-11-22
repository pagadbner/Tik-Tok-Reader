package com.myclivebot.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DesktopLiveChatHistoryStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun storesReadsAndClearsCommentsWithoutLosingFollowerUnknownState() {
        val store = DesktopLiveChatHistoryStore(tempDir.resolve("history.db"))
        try {
            val comment = LiveComment(
                userId = "user-1",
                displayName = "Ana",
                text = "Hola LIVE",
                avatarUrl = "https://example.com/avatar.jpg",
                isModerator = true,
                isFollower = null,
                receivedAt = 1234L,
                id = "comment-1",
                replyToDisplayName = "Host",
                contentLanguage = "es",
                profileUsername = "ana_01",
                replyToProfileUsername = "host"
            )
            assertTrue(store.addAsync(comment))
            val loaded = store.getAll()
            assertEquals(1, loaded.size)
            assertEquals("comment-1", loaded.single().id)
            assertEquals("Ana", loaded.single().displayName)
            assertEquals("Hola LIVE", loaded.single().text)
            assertEquals("user-1", loaded.single().userId)
            assertEquals("ana_01", loaded.single().profileUsername)
            assertTrue(loaded.single().isModerator)
            assertNull(loaded.single().isFollower)

            store.clear()
            assertEquals(0, store.count())
            assertFalse(store.getAll().isNotEmpty())
        } finally {
            store.close()
        }
    }
}
