package com.discord.widgets.servers

import com.discord.models.domain.ModelGuildIntegration
import com.discord.models.guild.Guild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure state derivation behind each reconstructed server-settings section.
 *
 * Every one of these screens closes itself rather than rendering a form the user may not submit, so
 * the gate is the part worth pinning: a permission regression would otherwise show up only as a
 * silently rejected PATCH.
 */
class ServerSettingsSectionsTest {

    private val guild = Guild(id = GUILD_ID, name = "Test Guild", ownerId = OWNER_ID)

    // region security

    @Test
    fun `security is owner-only, not permission-gated`() {
        val viewState = ServerSettingsSecurityViewModel.toViewState(
            ServerSettingsSecurityViewModel.StoreState(
                guildId = GUILD_ID,
                guildName = guild.name,
                isOwner = false,
                mfaLevel = 1,
            ),
        )
        assertEquals(ServerSettingsSecurityViewModel.ViewState.Unauthorized, viewState)
    }

    @Test
    fun `security derives the requirement from the guild's MFA level`() {
        fun stateFor(mfaLevel: Int) = ServerSettingsSecurityViewModel.toViewState(
            ServerSettingsSecurityViewModel.StoreState(
                guildId = GUILD_ID,
                guildName = guild.name,
                isOwner = true,
                mfaLevel = mfaLevel,
            ),
        ) as ServerSettingsSecurityViewModel.ViewState.Loaded

        assertFalse(stateFor(0).isMfaEnabled)
        assertTrue(stateFor(1).isMfaEnabled)
    }

    // endregion

    // region vanity url

    @Test
    fun `vanity url is unavailable to a guild without the feature`() {
        val viewState = ServerSettingsVanityUrlViewModel.toViewState(
            ServerSettingsVanityUrlViewModel.StoreState(
                guildId = GUILD_ID,
                guildName = guild.name,
                vanityUrlCode = "hello",
                vanityUrlUses = 3,
                canManage = true,
                canHaveVanityUrl = false,
            ),
        )
        assertEquals(ServerSettingsVanityUrlViewModel.ViewState.Unavailable, viewState)
    }

    @Test
    fun `vanity url carries the guild's code and the REST use count`() {
        val viewState = ServerSettingsVanityUrlViewModel.toViewState(
            ServerSettingsVanityUrlViewModel.StoreState(
                guildId = GUILD_ID,
                guildName = guild.name,
                vanityUrlCode = "hello",
                vanityUrlUses = 42,
                canManage = true,
                canHaveVanityUrl = true,
            ),
        ) as ServerSettingsVanityUrlViewModel.ViewState.Loaded

        assertEquals("hello", viewState.vanityUrl)
        assertEquals(42, viewState.vanityUrlUses)
    }

    // endregion

    // region integrations

    @Test
    fun `integrations distinguish not-yet-fetched from empty`() {
        fun stateFor(integrations: Map<Long, ModelGuildIntegration>?) =
            ServerSettingsIntegrationsViewModel.toViewState(
                ServerSettingsIntegrationsViewModel.StoreState(
                    guild = guild,
                    canManageServer = true,
                    integrations = integrations,
                ),
            ) as ServerSettingsIntegrationsViewModel.ViewState.Loaded

        assertTrue(stateFor(null).isLoading)
        assertFalse(stateFor(emptyMap()).isLoading)
    }

    @Test
    fun `only supported integration types get a row`() {
        val twitch = integration(id = 1, type = ModelGuildIntegration.TYPE_TWITCH)
        val youtube = integration(id = 2, type = ModelGuildIntegration.TYPE_YOUTUBE)
        // A bot application also arrives as an integration and has no row in 126.21.
        val application = integration(id = 3, type = "discord")

        val viewState = ServerSettingsIntegrationsViewModel.toViewState(
            ServerSettingsIntegrationsViewModel.StoreState(
                guild = guild,
                canManageServer = true,
                integrations = listOf(twitch, youtube, application).associateBy { it.id },
            ),
        ) as ServerSettingsIntegrationsViewModel.ViewState.Loaded

        assertEquals(listOf(1L, 2L), viewState.integrations.map { it.integration.id }.sorted())
    }

    // endregion

    // region edit integration

    @Test
    fun `a disabled integration has no settings to edit`() {
        val viewState = ServerSettingsEditIntegrationViewModel.toViewState(
            ServerSettingsEditIntegrationViewModel.StoreState(
                guild = guild,
                integration = integration(id = 1, type = ModelGuildIntegration.TYPE_TWITCH)
                    .copy(enabled = false),
                role = null,
                canManageServer = true,
            ),
        )
        assertEquals(ServerSettingsEditIntegrationViewModel.ViewState.Unavailable, viewState)
    }

    // endregion

    private fun integration(id: Long, type: String) = ModelGuildIntegration(
        id = id,
        name = "streamer",
        type = type,
        enabled = true,
        syncing = false,
        roleId = 0,
        enableEmoticons = true,
        expireBehavior = 0,
        expireGracePeriod = 1,
        user = null,
        account = null,
        syncedAt = null,
        subscriberCount = 0,
    )

    private companion object {
        const val GUILD_ID = 1L
        const val OWNER_ID = 7L
    }
}
