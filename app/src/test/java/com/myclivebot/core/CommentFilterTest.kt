package com.myclivebot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentFilterTest {
    @Test fun blocksConfiguredWords() {
        val filter = CommentFilter()
        val settings = ReaderSettings(blockedWords = setOf("spoiler"))
        assertFalse(filter.shouldRead(LiveComment("1", "Ana", "hay un SPOILER"), settings))
    }

    @Test fun blockedWordsMatchWholeWordsNotSubstrings() {
        val filter = CommentFilter()
        val settings = ReaderSettings(blockedWords = setOf("art"))
        assertTrue(filter.shouldRead(LiveComment("1", "Ana", "me gusta esta parte"), settings))
        assertFalse(filter.shouldRead(LiveComment("2", "Luis", "eso es arte"), settings))
        assertFalse(filter.shouldRead(LiveComment("3", "Mia", "¡ART!"), settings))
    }

    @Test fun blockedWordPhrasesRespectWordBoundaries() {
        val filter = CommentFilter()
        val settings = ReaderSettings(blockedWords = setOf("malas palabras"))
        assertTrue(filter.shouldRead(LiveComment("1", "Ana", "no digo malas palabrasx"), settings))
        assertFalse(filter.shouldRead(LiveComment("2", "Luis", "evita malas palabras, por favor"), settings))
    }

    @Test fun blocksIgnoredUsers() {
        val filter = CommentFilter()
        val settings = ReaderSettings(ignoredUsers = setOf("troll"))
        assertFalse(filter.shouldRead(LiveComment("troll", "Troll", "hola"), settings))
    }

    @Test fun suppressesRepeatedMessagesFromSameUserWithinWindow() {
        val filter = CommentFilter()
        val settings = ReaderSettings(suppressDuplicatesSeconds = 5)
        assertTrue(filter.shouldRead(LiveComment("1", "Ana", "hola", receivedAt = 10_000), settings))
        assertFalse(filter.shouldRead(LiveComment("1", "Ana", "hola", receivedAt = 12_000), settings))
        assertTrue(filter.shouldRead(LiveComment("1", "Ana", "hola", receivedAt = 16_000), settings))
    }

    @Test fun removesEmojiFromSpeechText() {
        val filter = CommentFilter()
        val result = filter.toSpeech(
            LiveComment("1", "Ana", "Hola 😍🔥 qué tal 👋"),
            ReaderSettings(readUsername = false, ignoreEmojis = true)
        )
        assertEquals("Hola qué tal", result)
    }

    @Test fun removesSymbolsAndPunctuationWhenEnabled() {
        val filter = CommentFilter()
        val result = filter.toSpeech(
            LiveComment("1", "Ana", "¡Hola!!! @todos #live 123"),
            ReaderSettings(readUsername = false, ignoreSymbols = true)
        )
        assertEquals("Hola todos live 123", result)
    }

    @Test fun catalogIncludesCortanaAndJarvisWithExpectedLanguages() {
        val cortana = PiperVoiceCatalog.find("es_MX-cortana-19669-epoch-high")
        val jarvis = PiperVoiceCatalog.find("en_us-jarvis-ucm-high")

        assertEquals("es", cortana.language)
        assertEquals("en", jarvis.language)
        assertTrue(cortana.expectedSha256?.isNotBlank() == true)
        assertTrue(jarvis.expectedSha256?.isNotBlank() == true)
        assertTrue(cortana.directDownload)
        assertTrue(jarvis.directDownload)
        assertTrue(jarvis.testPhrase.startsWith("Hello"))
    }

    @Test fun canReadOnlyTheUsername() {
        val filter = CommentFilter()
        val result = filter.toSpeech(
            LiveComment("1", "Ana_01", "Este comentario no debe leerse 😍"),
            ReaderSettings(onlyUsername = true, ignoreEmojis = true)
        )
        assertEquals("Ana_01", result)
    }

    @Test fun spokenUsernamePreservesDecorativeAndSpecialCharacters() {
        val filter = CommentFilter()
        val result = filter.toSpeech(
            LiveComment("1", "🔥A✦strid_99 Pérez✨", "hola"),
            ReaderSettings(readUsername = true)
        )
        assertEquals("A✦strid_99 Pérez dice: hola", result)
    }

    @Test fun symbolOnlyUsernameIsSkippedAndCommentIsStillRead() {
        val filter = CommentFilter()
        val result = filter.toSpeech(
            LiveComment("1", "🔥✦✨_-", "buenas noches"),
            ReaderSettings(readUsername = true)
        )
        assertEquals("usuario dice: buenas noches", result)
    }

    @Test fun symbolOnlyUsernameProducesNoSpeechInUsernameOnlyMode() {
        val filter = CommentFilter()
        val result = filter.toSpeech(
            LiveComment("1", "🔥✦✨_-", "comentario"),
            ReaderSettings(onlyUsername = true)
        )
        assertEquals("usuario", result)
    }
    @Test fun followerOnlyModeRejectsUnknownAndNonFollowers() {
        val filter = CommentFilter()
        val settings = ReaderSettings(followerOnlyChat = true)
        assertFalse(filter.shouldRead(LiveComment("1", "Ana", "hola", isFollower = null), settings))
        assertFalse(filter.shouldRead(LiveComment("2", "Luis", "hola", isFollower = false), settings))
        assertTrue(filter.shouldRead(LiveComment("3", "Mia", "hola", isFollower = true), settings))
    }

    @Test fun allowsLatinSpanishAndEnglishButRejectsOtherWritingSystems() {
        assertTrue(CommentLanguageFilter.isSupported("¡Qué tal!", "es-MX"))
        assertTrue(CommentLanguageFilter.isSupported("How are you?", "en-US"))
        assertFalse(CommentLanguageFilter.isSupported("Bonjour", "fr"))
        assertFalse(CommentLanguageFilter.isSupported("مرحبا كيف حالك", "ar"))
        assertFalse(CommentLanguageFilter.isSupported("Привет как дела", "ru"))
        assertFalse(CommentLanguageFilter.isSupported("नमस्ते आप कैसे हैं", "hi"))
        assertFalse(CommentLanguageFilter.isSupported("你好", "zh"))
        assertFalse(CommentLanguageFilter.isSupported("Привет как дела"))
    }

    @Test fun usernameOnlyModeDoesNotFilterByCommentBody() {
        val filter = CommentFilter()
        val settings = ReaderSettings(onlyUsername = true, blockedWords = setOf("spoiler"))
        val comment = LiveComment("1", "Ana", "esto contiene spoiler")
        assertTrue(filter.shouldRead(comment, settings))
        assertEquals("Ana", filter.toSpeech(comment, settings))
    }

}
