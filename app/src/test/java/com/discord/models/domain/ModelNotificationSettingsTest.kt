package com.discord.models.domain

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelNotificationSettingsTest {
    @Test
    fun explicitNullsUseWireDefaults() {
        val settings = Gson().fromJson(
            """{"suppress_everyone":null,"suppress_roles":null,"muted":null,"mute_config":null,"mobile_push":null,"message_notifications":null,"guild_id":null,"channel_overrides":null,"version":null,"notify_highlights":null}""",
            ModelNotificationSettings::class.java,
        )

        assertFalse(settings.isSuppressEveryone)
        assertFalse(settings.isSuppressRoles)
        assertFalse(settings.isMuted)
        assertTrue(settings.isMobilePush)
        assertEquals(ModelNotificationSettings.FREQUENCY_UNSET, settings.messageNotifications)
        assertEquals(0L, settings.guildId)
        assertEquals(emptyList<ModelNotificationSettings.ChannelOverride>(), settings.channelOverrides)
        assertEquals(0, settings.version)
        assertEquals(ModelNotificationSettings.HIGHLIGHTS_NULL, settings.notifyHighlights)
    }
}
