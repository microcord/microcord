package com.discord.widgets.servers

import com.discord.api.role.GuildRole
import com.discord.models.domain.ModelGuildIntegration
import com.discord.models.guild.Guild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ServerSettingsIntegrationsViewModelTest {
    @Test
    fun `list requires manage server and only includes supported providers`() {
        val guild = Guild(id = 1, name = "Guild")
        val twitch = integration(10, ModelGuildIntegration.TYPE_TWITCH)
        val youtube = integration(11, ModelGuildIntegration.TYPE_YOUTUBE)
        val bot = integration(12, "discord")

        assertSame(
            ServerSettingsIntegrationsViewModel.ViewState.Unauthorized,
            ServerSettingsIntegrationsViewModel.toViewState(
                ServerSettingsIntegrationsViewModel.StoreState(guild, false, emptyMap()),
            ),
        )

        val loaded = ServerSettingsIntegrationsViewModel.toViewState(
            ServerSettingsIntegrationsViewModel.StoreState(
                guild,
                true,
                listOf(twitch, youtube, bot).associateBy { it.id },
            ),
        ) as ServerSettingsIntegrationsViewModel.ViewState.Loaded

        assertEquals(listOf(twitch, youtube), loaded.integrations.map { it.integration })
    }

    @Test
    fun `editor requires an enabled integration and preserves its synced role`() {
        val guild = Guild(id = 1, name = "Guild")
        val role = role(20)
        val enabled = integration(10, ModelGuildIntegration.TYPE_TWITCH, enabled = true)

        val loaded = ServerSettingsEditIntegrationViewModel.toViewState(
            ServerSettingsEditIntegrationViewModel.StoreState(guild, enabled, role, true),
        ) as ServerSettingsEditIntegrationViewModel.ViewState.Loaded

        assertEquals(role, loaded.role)
        assertSame(
            ServerSettingsEditIntegrationViewModel.ViewState.Unavailable,
            ServerSettingsEditIntegrationViewModel.toViewState(
                ServerSettingsEditIntegrationViewModel.StoreState(
                    guild,
                    enabled.copy(enabled = false),
                    role,
                    true,
                ),
            ),
        )
    }

    private fun integration(id: Long, type: String, enabled: Boolean = true) = ModelGuildIntegration(
        id = id,
        name = "account-$id",
        type = type,
        enabled = enabled,
        syncing = false,
        roleId = 20,
        enableEmoticons = false,
        expireBehavior = 0,
        expireGracePeriod = 1,
        user = null,
        account = null,
        syncedAt = null,
        subscriberCount = 0,
    )

    private fun role(id: Long) = GuildRole(
        position = 1,
        name = "role-$id",
        id = id,
        color = 0,
        hoist = false,
        permissions = 0,
        mentionable = false,
        managed = true,
        icon = null,
        tags = null,
        unicodeEmoji = null,
    )
}
