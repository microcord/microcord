package com.discord.widgets.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationMuteSettingsViewTest {
    @Test
    fun viewStatePreservesReferenceContract() {
        val state = NotificationMuteSettingsView.ViewState(
            isMuted = false,
            muteEndTime = "2026-07-24T12:00:00",
            rawMuteText = "Mute",
            rawUnmuteText = "Unmute",
            rawMutedStatusText = "Muted",
            rawMutedUntilStatusResId = 42,
        )

        assertNull(state.muteDescriptionText)
        assertEquals(state.copy(isMuted = true), state.copy().copy(isMuted = true))
        assertEquals("Mute", state.component3())
        assertEquals(42, state.component6())
    }
}
