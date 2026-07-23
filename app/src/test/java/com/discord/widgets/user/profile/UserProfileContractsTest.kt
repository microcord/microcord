package com.discord.widgets.user.profile

import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.api.voice.state.StageRequestToSpeakState
import com.discord.models.domain.emoji.toCodePoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileContractsTest {
    @Test
    fun preservesConnectionAndPresenceContracts() {
        val account = ConnectedAccount(id = "42", name = "user", visibility = ConnectedAccount.VISIBLE, type = "github")
        assertEquals("42", UserProfileConnectionsView.ConnectedAccountItem(account).key)
        assertTrue(StageRequestToSpeakState.NONE.canBeInvitedToSpeak)
        assertFalse(StageRequestToSpeakState.ON_STAGE.canBeInvitedToSpeak)
        assertEquals("1f44b_1f3fd", "👋🏽".toCodePoints())
    }
}
