package com.myclivebot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun commandMatchesFirstTokenAndExpandsSafeUsername() {
        val commands = ChatCommandParser.parseConfig("!discord=Únete al Discord, {user}.")
        val response = ChatCommandParser.responseFor("!DISCORD ahora", "🔥Ana_99 Pérez✨", commands)

        assertEquals("Únete al Discord, Ana Pérez.", response)
        assertNull(ChatCommandParser.responseFor("!discordextra", "Ana", commands))
    }

    @Test fun welcomeRunsOnlyOncePerUserInOneSession() {
        val tracker = WelcomeMessageTracker()
        val settings = ReaderSettings(
            welcomeEnabled = true,
            welcomeTemplate = "¡Qué gusto verte, {user}!"
        )
        val first = LiveComment("42", "Ana_01", "hola")
        val another = LiveComment("43", "Luis", "buenas")

        assertEquals("¡Qué gusto verte, Ana!", tracker.messageFor(first, settings))
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
        assertEquals("Gracias Mia por los 5 regalos", GiftAlertRules.render(atThreshold, settings))
    }

    @Test fun giftAlertsRespectExistingMasterSwitchAndIgnoreNonGiftEvents() {
        val gift = LiveEvent(LiveEventType.GIFT, "Mia", "x10")
        val follow = LiveEvent(LiveEventType.FOLLOW, "Mia")

        assertFalse(GiftAlertRules.shouldAnnounce(gift, ReaderSettings(readGifts = false)))
        assertFalse(GiftAlertRules.shouldAnnounce(follow, ReaderSettings()))
        assertNull(GiftAlertRules.render(follow, ReaderSettings()))
    }
}
