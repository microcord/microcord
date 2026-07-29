package com.discord.widgets.settings.connections

import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.api.connectedaccounts.ConnectedAccountIntegration
import com.discord.api.connectedaccounts.ConnectedIntegrationAccount
import com.discord.api.connectedaccounts.ConnectedIntegrationGuild
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSettingsUserConnectionsViewModelTest {
    @Test
    fun `pending join status overrides integration state`() {
        val account = ConnectedAccount(
            id = "account",
            name = "Streamer",
            type = "twitch",
            integrations = listOf(
                ConnectedAccountIntegration(
                    id = "integration",
                    type = "twitch",
                    account = ConnectedIntegrationAccount("provider", "streamer"),
                    guild = ConnectedIntegrationGuild(42L, null, "Guild"),
                ),
            ),
        )

        val result = WidgetSettingsUserConnectionsViewModel.combineState(
            WidgetSettingsUserConnectionsViewModel.StoreState(listOf(account), emptyMap()),
            mapOf("integration" to WidgetSettingsUserConnectionsViewModel.JoinStatus.Joining),
        )

        assertEquals(
            WidgetSettingsUserConnectionsViewModel.JoinStatus.Joining,
            result.single().integrationGuildJoinStatus.getValue("integration"),
        )
    }
}
