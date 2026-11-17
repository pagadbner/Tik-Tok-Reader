package com.myclivebot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

        assertEquals("Únete al Discord, Ana_99 Pérez.", response)
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
                    replyToDisplayName = "Karla",
                    profileUsername = "ana_handle",
                    replyToProfileUsername = "karla_handle"
                )
            )
            // The option is off by default, so existing display-name speech is preserved.
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
        assertEquals("Ana_99", ChatCommandParser.spokenUsername("🔥Ana_99✨"))
        assertEquals("usuario", ChatCommandParser.spokenUsername("♡🔥✨"))
        assertEquals(
            "Ana_99 dice: hola",
            CommentFilter().toSpeech(
                LiveComment("id-1", "🔥Ana_99✨", "hola"),
                ReaderSettings(readUsername = true)
            )
        )
    }

    @Test fun followAndGiftAlertsAreDebouncedAndUseSafeNames() {
        val spoken = Collections.synchronizedList(mutableListOf<String>())
        val spokenLatch = CountDownLatch(2)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) {
                spoken += text
                spokenLatch.countDown()
            }
            override suspend fun stop() = Unit
        }
        val queue = SpeechQueueWorker(engine, scope, capacity = 4)
        val pipeline = CommentPipeline(
            CommentFilter(), queue, ReaderSettings(),
            followBatchWindowMs = 30L,
            giftBatchWindowMs = 30L
        )

        try {
            assertTrue(pipeline.onEvent(LiveEvent(LiveEventType.FOLLOW, "🔥Ana_99✨", userId = "follow-1")))
            assertTrue(pipeline.onEvent(LiveEvent(LiveEventType.GIFT, "♡", userId = "gift-1")))
            // Both alerts wait for their quiet window instead of speaking immediately.
            assertTrue(spoken.isEmpty())
            assertTrue(spokenLatch.await(2, TimeUnit.SECONDS))
            assertTrue(spoken.contains("Gracias por seguirme, Ana_99"))
            assertTrue(spoken.contains("Gracias usuario por tu regalo"))
        } finally {
            pipeline.close()
            queue.close()
            scope.cancel()
        }
    }

    @Test fun followEventsAreGroupedDeduplicatedAndLimitedToThreeNames() {
        val spoken = Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) {
                spoken += text
                latch.countDown()
            }
            override suspend fun stop() = Unit
        }
        val queue = SpeechQueueWorker(engine, scope, capacity = 4)
        val pipeline = CommentPipeline(
            CommentFilter(), queue, ReaderSettings(readFollows = true),
            followBatchWindowMs = 40L
        )
        try {
            assertTrue(pipeline.onEvent(LiveEvent(LiveEventType.FOLLOW, "Ana", userId = "f1")))
            assertTrue(pipeline.onEvent(LiveEvent(LiveEventType.FOLLOW, "Luis", userId = "f2")))
            assertTrue(pipeline.onEvent(LiveEvent(LiveEventType.FOLLOW, "Mia", userId = "f3")))
            assertTrue(pipeline.onEvent(LiveEvent(LiveEventType.FOLLOW, "Noah", userId = "f4")))
            assertTrue(pipeline.onEvent(LiveEvent(LiveEventType.FOLLOW, "Ana repetida", userId = "f1")))
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(
                "Gracias por seguirme, Ana, Luis, Mia, y otra persona",
                spoken.single()
            )
        } finally {
            pipeline.close()
            queue.close()
            scope.cancel()
        }
    }

    @Test fun giftEventsFromSeveralUsersAreGroupedWithAggregatedRepeatCounts() {
        val spoken = Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) {
                spoken += text
                latch.countDown()
            }
            override suspend fun stop() = Unit
        }
        val queue = SpeechQueueWorker(engine, scope, capacity = 4)
        val pipeline = CommentPipeline(
            CommentFilter(), queue, ReaderSettings(readGifts = true),
            giftBatchWindowMs = 40L
        )
        try {
            assertTrue(pipeline.onEvent(LiveEvent(LiveEventType.GIFT, "Ana", "x2", userId = "g1")))
            assertTrue(pipeline.onEvent(LiveEvent(LiveEventType.GIFT, "Ana", "x1", userId = "g1")))
            assertTrue(pipeline.onEvent(LiveEvent(LiveEventType.GIFT, "Luis", "x1", userId = "g2")))
            assertTrue(spoken.isEmpty())
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals("Gracias Ana (x3), Luis por los 4 regalos", spoken.single())
        } finally {
            pipeline.close()
            queue.close()
            scope.cancel()
        }
    }

    @Test fun groupedGiftTemplateRetainsUserAndCountPlaceholders() {
        assertEquals(
            "Gracias Ana (x2), Luis por los 3 regalos",
            GiftAlertRules.renderBatch(
                listOf("Ana" to 2L, "Luis" to 1L),
                totalCount = 3L,
                additionalUsers = 0,
                settings = ReaderSettings(giftAlertTemplate = "Gracias {user} por los {count} regalos")
            )
        )
    }


    @Test fun giftAlertsRespectExistingMasterSwitchAndIgnoreNonGiftEvents() {
        val gift = LiveEvent(LiveEventType.GIFT, "Mia", "x10")
        val follow = LiveEvent(LiveEventType.FOLLOW, "Mia")

        assertFalse(GiftAlertRules.shouldAnnounce(gift, ReaderSettings(readGifts = false)))
        assertFalse(GiftAlertRules.shouldAnnounce(follow, ReaderSettings()))
        assertNull(GiftAlertRules.render(follow, ReaderSettings()))
    }
    @Test fun shareAlertsStopAfterTwoPerUserAndRespectFollowerFilter() {
        val tracker = ShareAlertTracker()
        val followerOnly = ReaderSettings(readShares = true, shareFollowerOnly = true)
        val share = LiveEvent(LiveEventType.SHARE, "Ana", userId = "user-1", isFollower = false)
        assertNull(tracker.render(share, followerOnly))

        val followerShare = share.copy(isFollower = true)
        val firstAnnouncement = tracker.render(followerShare, followerOnly)
        assertEquals("@Ana ha compartido el live", firstAnnouncement)
        tracker.confirmQueued(followerShare)
        val secondAnnouncement = followerShare.copy(receivedAt = followerShare.receivedAt + 1)
        assertEquals("@Ana ha compartido el live", tracker.render(secondAnnouncement, followerOnly))
        tracker.confirmQueued(secondAnnouncement)
        assertNull(tracker.render(secondAnnouncement.copy(receivedAt = secondAnnouncement.receivedAt + 1), followerOnly))
        assertNull(tracker.render(followerShare, ReaderSettings(readShares = false)))
    }

    @Test fun shareAlertRetriesWhenPrioritySpeechQueueWasFull() {
        val spoken = mutableListOf<String>()
        val releaseFirstSpeech = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) {
                if (text == "bloqueo") releaseFirstSpeech.await()
                spoken += text
            }
            override suspend fun stop() = Unit
        }
        val queue = SpeechQueueWorker(engine, scope, capacity = 4, priorityCapacity = 1)
        val pipeline = CommentPipeline(CommentFilter(), queue, ReaderSettings(readShares = true))
        val share = LiveEvent(
            LiveEventType.SHARE, "Ana", receivedAt = 30_000L, userId = "user-1"
        )

        try {
            assertTrue(queue.enqueue("bloqueo", priority = true))
            assertTrue(queue.enqueue("ocupado", priority = true))
            assertFalse(pipeline.onEvent(share))

            releaseFirstSpeech.complete(Unit)
            assertTrue(pipeline.onEvent(share.copy(receivedAt = 30_001L)))
            assertTrue(spoken.contains("@Ana ha compartido el live"))
        } finally {
            releaseFirstSpeech.complete(Unit)
            queue.close()
            scope.cancel()
        }
    }

    @Test fun rejectedShareAlertDoesNotConsumePerUserAnnouncementLimit() {
        val tracker = ShareAlertTracker()
        val settings = ReaderSettings(readShares = true)
        val first = LiveEvent(
            LiveEventType.SHARE, "Ana", receivedAt = 20_000L, userId = "user-1"
        )

        assertEquals("@Ana ha compartido el live", tracker.render(first, settings))
        tracker.rollbackIfNotQueued(first)

        val retry = first.copy(receivedAt = 20_001L)
        assertEquals("@Ana ha compartido el live", tracker.render(retry, settings))
        tracker.confirmQueued(retry)

        val second = retry.copy(receivedAt = 20_002L)
        assertEquals("@Ana ha compartido el live", tracker.render(second, settings))
        tracker.confirmQueued(second)
        assertNull(tracker.render(second.copy(receivedAt = 20_003L), settings))
    }

    @Test fun likeAlertsAccumulatePerUserAndRepeatOnlyAfterTenMinutes() {
        val tracker = LikeAlertTracker()
        val settings = ReaderSettings(readLikes = true)
        val start = 1_000_000L
        fun likes(count: Long, at: Long) = LiveEvent(
            LiveEventType.LIKE, "Astrid", count.toString(),
            receivedAt = at, userId = "user-1"
        )

        assertNull(tracker.render(likes(999, start), settings))
        val firstMilestone = likes(1, start + 1)
        assertEquals(
            "Gracias por dar me gusta @Astrid, tu apoyo es importante",
            tracker.render(firstMilestone, settings)
        )
        tracker.confirmQueued(firstMilestone)
        // The cooldown starts only after the queue accepts the first thank-you.
        assertNull(tracker.render(likes(50, start + 1 + LikeAlertTracker.COOLDOWN_MS - 1), settings))
        val nextMilestone = likes(1, start + 1 + LikeAlertTracker.COOLDOWN_MS)
        assertEquals(
            "Gracias por dar me gusta @Astrid, tu apoyo es importante",
            tracker.render(nextMilestone, settings)
        )
        tracker.confirmQueued(nextMilestone)
    }

    @Test fun joinAlertsRespectFollowerOnlySettingAndUseSharedQueue() {
        val spoken = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) { spoken += text }
            override suspend fun stop() = Unit
        }
        val queue = SpeechQueueWorker(engine, scope, capacity = 4)
        val pipeline = CommentPipeline(
            CommentFilter(), queue,
            ReaderSettings(readJoins = true, joinFollowerOnly = true)
        )
        try {
            assertFalse(pipeline.onEvent(LiveEvent(LiveEventType.JOIN, "Luis", userId = "2", isFollower = false)))
            assertTrue(pipeline.onEvent(LiveEvent(LiveEventType.JOIN, "Ana", userId = "1", isFollower = true)))
            assertEquals(listOf("@Ana se ha unido al live"), spoken)
        } finally {
            queue.close()
            scope.cancel()
        }
    }


    @Test fun rejectedLikeAlertDoesNotConsumeTheTenMinuteCooldown() {
        val tracker = LikeAlertTracker()
        val settings = ReaderSettings(readLikes = true)
        val event = LiveEvent(LiveEventType.LIKE, "Astrid", "1000", receivedAt = 10_000L, userId = "user-1")

        assertEquals(
            "Gracias por dar me gusta @Astrid, tu apoyo es importante",
            tracker.render(event, settings)
        )
        tracker.rollbackIfNotQueued(event)
        val retry = event.copy(detail = "1", receivedAt = 10_001L)
        assertEquals(
            "Gracias por dar me gusta @Astrid, tu apoyo es importante",
            tracker.render(retry, settings)
        )
        tracker.confirmQueued(retry)
    }

    @Test fun likeThanksWorkInFollowerOnlyAndEveryoneChatModes() {
        listOf(false, true).forEach { followerOnly ->
            val spoken = mutableListOf<String>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val engine = object : SpeechEngine {
                override suspend fun initialize() = Unit
                override suspend fun speak(text: String) { spoken += text }
                override suspend fun stop() = Unit
            }
            val queue = SpeechQueueWorker(engine, scope, capacity = 4)
            val pipeline = CommentPipeline(
                CommentFilter(), queue,
                ReaderSettings(readLikes = true, followerOnlyChat = followerOnly)
            )
            try {
                assertTrue(pipeline.onEvent(
                    LiveEvent(LiveEventType.LIKE, "🔥Ana_99✨", "1000", userId = "user-1", isFollower = null)
                ))
                assertEquals(
                    listOf("Gracias por dar me gusta @Ana_99, tu apoyo es importante"),
                    spoken
                )
            } finally {
                queue.close()
                scope.cancel()
            }
        }
    }
    @Test fun welcomeUsesProfileUsernameWhenTheSettingIsEnabled() {
        val spoken = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) { spoken += text }
            override suspend fun stop() = Unit
        }
        val queue = SpeechQueueWorker(engine, scope, capacity = 4)
        val settings = ReaderSettings(
            readUsername = false,
            welcomeEnabled = true,
            welcomeTemplate = "¡Bienvenido {user}!",
            useProfileUsername = true
        )
        val pipeline = CommentPipeline(CommentFilter(), queue, settings)
        try {
            assertTrue(pipeline.onComment(
                LiveComment("welcome-1", "Karina", "hola", profileUsername = "karina_fu82")
            ))
            assertEquals(listOf("¡Bienvenido karina_fu82!", "hola"), spoken)
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test fun customCommandUserPlaceholderUsesSelectedProfileUsername() {
        val spoken = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) { spoken += text }
            override suspend fun stop() = Unit
        }
        val queue = SpeechQueueWorker(engine, scope, capacity = 4)
        val pipeline = CommentPipeline(
            CommentFilter(), queue,
            ReaderSettings(
                readUsername = false,
                customCommandsEnabled = true,
                customCommands = mapOf("!hola" to "Hola {user}"),
                useProfileUsername = true
            )
        )
        try {
            assertTrue(pipeline.onComment(
                LiveComment("command-1", "Karina", "!hola", isModerator = true, profileUsername = "karina_fu82")
            ))
            assertEquals(listOf("Hola karina_fu82"), spoken)
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test fun replyUsesSelectedProfileUsernamesForBothParticipants() {
        val spoken = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : SpeechEngine {
            override suspend fun initialize() = Unit
            override suspend fun speak(text: String) { spoken += text }
            override suspend fun stop() = Unit
        }
        val queue = SpeechQueueWorker(engine, scope, capacity = 4)
        val pipeline = CommentPipeline(
            CommentFilter(), queue,
            ReaderSettings(readUsername = true, useProfileUsername = true)
        )
        try {
            assertTrue(pipeline.onComment(
                LiveComment(
                    userId = "reply-author",
                    displayName = "Karina",
                    text = "@luis_01 gracias por el dato",
                    profileUsername = "karina_fu82",
                    replyToDisplayName = "Luis",
                    replyToProfileUsername = "luis_01"
                )
            ))
            assertEquals(
                listOf("karina_fu82 le respondió a luis_01: gracias por el dato"),
                spoken
            )
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test fun profileUsernameSelectionAndDisplayNameFallbackAreConsistent() {
        val filter = CommentFilter()
        val withHandle = LiveComment("fallback-1", "Karina", "hola", profileUsername = "karina_fu82")
        assertEquals("karina_fu82", filter.usernameForSpeech(withHandle, ReaderSettings(useProfileUsername = true)))
        assertEquals("Karina", filter.usernameForSpeech(withHandle, ReaderSettings(useProfileUsername = false)))

        val withoutHandle = withHandle.copy(profileUsername = "")
        assertEquals("Karina", filter.usernameForSpeech(withoutHandle, ReaderSettings(useProfileUsername = true)))
    }

}
