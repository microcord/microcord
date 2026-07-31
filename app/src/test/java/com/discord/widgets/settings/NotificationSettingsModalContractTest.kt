package com.discord.widgets.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSettingsModalContractTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }

    @Test
    fun `notification settings use reconstructed Discord surfaces`() {
        val channel = source("widgets/channels/settings/WidgetChannelNotificationSettings.kt")
        val server = source("widgets/servers/WidgetServerNotifications.kt")
        val muteSheet = source("widgets/settings/WidgetMuteSettingsSheet.kt")
        val overrideSelector = source("widgets/servers/WidgetServerNotificationsOverrideSelector.kt")

        listOf(channel, server, muteSheet, overrideSelector).forEach { source ->
            FORBIDDEN_PLATFORM_SURFACES.forEach { assertFalse(source.contains(it)) }
        }

        assertTrue(muteSheet.contains("class WidgetMuteSettingsSheet : AppBottomSheet()"))
        listOf("900_000L", "3_600_000L", "28_800_000L", "86_400_000L", "MUTE_DURATION_ALWAYS")
            .forEach { assertTrue(muteSheet.contains(it)) }
        assertTrue(server.contains("WidgetMuteSettingsSheet.showForGuild"))
        assertTrue(channel.contains("WidgetMuteSettingsSheet.showForChannel"))
    }

    @Test
    fun `notification levels restore checked state and DMs remain mute only`() {
        val channel = source("widgets/channels/settings/WidgetChannelNotificationSettings.kt")
        val server = source("widgets/servers/WidgetServerNotifications.kt")
        val viewModel = source("widgets/settings/MuteSettingsSheetViewModel.kt")

        listOf(channel, server).forEach { source ->
            assertTrue(source.contains("RadioManager("))
            assertTrue(source.contains("FREQUENCY_ALL"))
            assertTrue(source.contains("FREQUENCY_MENTIONS"))
            assertTrue(source.contains("FREQUENCY_NOTHING"))
        }
        assertTrue(channel.contains("channel_settings_notifications_frequency_wrap).isVisible = !isPrivate"))
        assertTrue(channel.contains("if (isPrivate)"))
        assertTrue(channel.contains("createPrivate(channel, it)"))
        assertTrue(viewModel.contains("StoreStream.getThreadsJoined().observeJoinedThread(it)"))
    }

    @Test
    fun `notification sheets restore identity from arguments`() {
        val channel = source("widgets/channels/settings/WidgetChannelNotificationSettings.kt")
        val server = source("widgets/servers/WidgetServerNotifications.kt")
        val muteSheet = source("widgets/settings/WidgetMuteSettingsSheet.kt")

        assertTrue(channel.contains("putLong(ARG_CHANNEL_ID, channelId)"))
        assertTrue(server.contains("putLong(EXTRA_GUILD_ID, guildId)"))
        assertTrue(muteSheet.contains("putLong(ARG_GUILD_ID, guildId)"))
        assertTrue(muteSheet.contains("putLong(ARG_CHANNEL_ID, channelId)"))
        assertFalse(muteSheet.contains("isCancelable = false"))
    }

    private fun source(path: String) =
        File(root, "app/src/main/java/com/discord/$path").readText()

    private companion object {
        val FORBIDDEN_PLATFORM_SURFACES = listOf(
            "AlertDialog",
            "MaterialAlertDialogBuilder",
            "PopupMenu",
        )
    }
}
