package com.myclivebot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test

class LiveAutomationTest {
    @Test fun parsesOnlyValidCommandLinesAndNormalizesNames() {
        val commands = ChatCommandParser.parseConfig(
            """
            !Discord=Entra a mi comunidad
            !youtube=Videos nuevos en mi canal
            !bad command=Esto no debe cargarse
            texto sin separador
            !empty=
            """.trimIndent()
        )

        assertEquals(2, commands.size)
        assertEquals("Entra a mi comunidad", commands["!discord"])
        assertFalse(commands.containsKey("!bad command"))
    }

    @Test fun parsesMultipleCommandsTransferredAsSeparateIntentLines() {
        val transferred = listOf(
            "!redes=Mis redes están en mi perfil",
            "!discord=Únete a mi comunidad de Discord",
            "!youtube=Encuentra mis videos en YouTube"
        ).joinToString("\n")
        val commands = ChatCommandParser.parseConfig(transferred)

        assertEquals(3, commands.size)
        assertEquals("Mis redes están en mi perfil", ChatCommandParser.responseFor("!redes", "Ana", commands, isModerator = true))
        assertEquals("Únete a mi comunidad de Discord", ChatCommandParser.responseFor("!discord", "Ana", commands, isModerator = true))
        assertEquals("Encuentra mis videos en YouTube", ChatCommandParser.responseFor("!youtube", "Ana", commands, isModerator = true))
    }

    @Test fun commandMatchesFirstTokenAndExpandsSafeUsername() {
        val commands = ChatCommandParser.parseConfig("!discord=Únete al Discord, {user}.")
        val response = ChatCommandParser.responseFor("!DISCORD ahora", "🔥Ana_99 Pérez✨", commands, isModerator = true)

        assertEquals("Únete al Discord, 🔥Ana_99 Pérez✨.", response)
        assertNull(ChatCommandParser.responseFor("!discordextra", "Ana", commands, isModerator = true))
    }


    @Test fun customCommandsAreIgnoredForNonModerators() {
        val commands = ChatCommandParser.parseConfig("!redes=Mis redes\n!discord=Mi Discord")
        assertNull(ChatCommandParser.responseFor("!redes", "Viewer", commands, isModerator = false))
        assertNull(ChatCommandParser.responseFor("!discord", "Viewer", commands, isModerator = false))
        assertEquals("Mi Discord", ChatCommandParser.responseFor("!discord", "Mod", commands, isModerator = true))
    }


    @Test fun speakerIsAnnouncedOnceUntilAnotherUserTakesTheFloor() {
        val tracker = CommentSpeakerTracker()
        val ana = LiveComment("user-1", "Ana", "hola")
        val luis = LiveComment("user-2", "Luis", "buenas")

        assertTrue(tracker.isNewSpeaker(ana))
        assertFalse(tracker.isNewSpeaker(ana.copy(text = "¿cómo están?")))
        assertTrue(tracker.isNewSpeaker(luis))
        assertTrue(tracker.isNewSpeaker(ana.copy(text = "volví")))
        assertFalse(tracker.isNewSpeaker(ana.copy(text = "otra vez")))
    }

    @Test fun speakerTrackerFallsBackToDisplayNameWhenUserIdIsMissing() {
        val tracker = CommentSpeakerTracker()
        val first = LiveComment("", "Ana", "hola")
        assertTrue(tracker.isNewSpeaker(first))
        assertFalse(tracker.isNewSpeaker(first.copy(text = "otra frase")))
    }

    @Test fun replyCommentAnnouncesTargetWithoutRepeatingTheAtMention() {
        val spoken = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) { spoken += text }
            override suspend fun stop() = Unit
        }
        val queue = SpeechQueueWorker(engine, scope, capacity = 4)
        val pipeline = CommentPipeline(CommentFilter(), queue, ReaderSettings(readUsername = true))

        try {
            pipeline.onComment(
                LiveComment(
                    userId = "ana-id",
                    displayName = "Ana",
                    text = "@Karla gracias por el dato",
                    replyToDisplayName = "Karla"
                )
            )
            assertEquals(listOf("Ana le respondió a Karla: gracias por el dato"), spoken)
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test fun welcomeRunsOnlyOncePerUserInOneSession() {
        val tracker = WelcomeMessageTracker()
        val settings = ReaderSettings(
            welcomeEnabled = true,
            welcomeTemplate = "¡Qué gusto verte, {user}!"
        )
        val first = LiveComment("42", "Ana_01", "hola")
        val another = LiveComment("43", "Luis", "buenas")

        assertEquals("¡Qué gusto verte, Ana_01!", tracker.messageFor(first, settings))
        assertNull(tracker.messageFor(first.copy(text = "otra vez"), settings))
        assertEquals("¡Qué gusto verte, Luis!", tracker.messageFor(another, settings))
    }

    @Test fun disabledWelcomeDoesNotTrackOrEmit() {
        val tracker = WelcomeMessageTracker()
        val comment = LiveComment("42", "Ana", "hola")

        assertNull(tracker.messageFor(comment, ReaderSettings(welcomeEnabled = false)))
        assertEquals(
            "¡Bienvenido Ana!",
            tracker.messageFor(comment, ReaderSettings(welcomeEnabled = true, welcomeTemplate = "¡Bienvenido {user}!"))
        )
    }

    @Test fun giftThresholdAndTemplateUseOnlyAvailableRepeatCount() {
        val settings = ReaderSettings(
            giftMinimumRepeatCount = 5,
            giftAlertTemplate = "Gracias {user} por los {count} regalos"
        )
        val belowThreshold = LiveEvent(LiveEventType.GIFT, "Mia", "x4")
        val atThreshold = LiveEvent(LiveEventType.GIFT, "Mia_22", "x5")

        assertFalse(GiftAlertRules.shouldAnnounce(belowThreshold, settings))
        assertTrue(GiftAlertRules.shouldAnnounce(atThreshold, settings))
        assertEquals("Gracias Mia_22 por los 5 regalos", GiftAlertRules.render(atThreshold, settings))
    }

    @Test fun usernamesWithSymbolsUseGenericSpokenName() {
        assertEquals("Ana Pérez", ChatCommandParser.spokenUsername("Ana Pérez"))
        assertEquals("🔥Ana_99✨", ChatCommandParser.spokenUsername("🔥Ana_99✨"))
        assertEquals("usuario", ChatCommandParser.spokenUsername("♡🔥✨"))
        assertEquals(
            "🔥Ana_99✨ dice: hola",
            CommentFilter().toSpeech(
                LiveComment("id-1", "🔥Ana_99✨", "hola"),
                ReaderSettings(readUsername = true)
            )
        )
    }

    @Test fun followAndGiftAlertsUseSharedSpeechQueueWithSafeNames() {
        val spoken = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) { spoken += text }
            override suspend fun stop() = Unit
        }
        val queue = SpeechQueueWorker(engine, scope, capacity = 4)
        val pipeline = CommentPipeline(CommentFilter(), queue, ReaderSettings())

        try {
            assertTrue(pipeline.onEvent(LiveEvent(LiveEventType.FOLLOW, "🔥Ana_99✨")))
            assertTrue(pipeline.onEvent(LiveEvent(LiveEventType.GIFT, "♡", "")))
            assertEquals(
                listOf("usuario te ha seguido", "usuario te envió un regalo"),
                spoken
            )
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test fun giftAlertsRespectExistingMasterSwitchAndIgnoreNonGiftEvents() {
        val gift = LiveEvent(LiveEventType.GIFT, "Mia", "x10")
        val follow = LiveEvent(LiveEventType.FOLLOW, "Mia")

        assertFalse(GiftAlertRules.shouldAnnounce(gift, ReaderSettings(readGifts = false)))
        assertFalse(GiftAlertRules.shouldAnnounce(follow, ReaderSettings()))
        assertNull(GiftAlertRules.render(follow, ReaderSettings()))
    }
}
