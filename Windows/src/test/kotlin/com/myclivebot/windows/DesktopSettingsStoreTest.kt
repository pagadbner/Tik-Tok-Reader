package com.myclivebot.windows

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopSettingsStoreTest {
    @Test
    fun automationSettingsSurviveSaveAndReload() {
        val original = DesktopSettingsStore.load()
        val expected = original.copy(
            suppressDuplicatesSeconds = 17L,
            shareFollowerOnly = true,
            joinFollowerOnly = true,
            randomVoices = true,
            customCommandsEnabled = true,
            customCommands = mapOf("!hola" to "Hola {user}", "!redes" to "Sigue {user}"),
            welcomeEnabled = true,
            welcomeTemplate = "¡Qué gusto verte, {user}!",
            giftMinimumRepeatCount = 4,
            giftAlertTemplate = "Gracias {user} por {count}",
            useProfileUsername = true
        )
        try {
            DesktopSettingsStore.save(expected)
            val actual = DesktopSettingsStore.load()
            assertEquals(expected.suppressDuplicatesSeconds, actual.suppressDuplicatesSeconds)
            assertEquals(expected.shareFollowerOnly, actual.shareFollowerOnly)
            assertEquals(expected.joinFollowerOnly, actual.joinFollowerOnly)
            assertEquals(expected.randomVoices, actual.randomVoices)
            assertEquals(expected.customCommandsEnabled, actual.customCommandsEnabled)
            assertEquals(expected.customCommands, actual.customCommands)
            assertEquals(expected.welcomeEnabled, actual.welcomeEnabled)
            assertEquals(expected.welcomeTemplate, actual.welcomeTemplate)
            assertEquals(expected.giftMinimumRepeatCount, actual.giftMinimumRepeatCount)
            assertEquals(expected.giftAlertTemplate, actual.giftAlertTemplate)
            assertEquals(expected.useProfileUsername, actual.useProfileUsername)
        } finally {
            DesktopSettingsStore.save(original)
        }
    }
}
