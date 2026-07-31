package com.discord.widgets.servers

import com.discord.api.guild.GuildExplicitContentFilter
import com.discord.api.guild.GuildFeature
import com.discord.api.guild.GuildVerificationLevel
import com.discord.models.guild.Guild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSettingsModerationViewModelTest {
    private val guild = Guild(id = 1, name = "Test Guild", ownerId = 7)

    @Test
    fun `missing or unauthorized guild closes moderation`() {
        assertEquals(
            ServerSettingsModerationViewModel.ViewState.Unauthorized,
            state(guild = null, canManage = true),
        )
        assertEquals(
            ServerSettingsModerationViewModel.ViewState.Unauthorized,
            state(guild = guild, canManage = false),
        )
    }

    @Test
    fun `loaded moderation preserves levels and community floor`() {
        val configured = guild.copy(
            verificationLevel = GuildVerificationLevel.HIGH,
            explicitContentFilter = GuildExplicitContentFilter.SOME,
        )
        val loaded = state(configured, canManage = true)
            as ServerSettingsModerationViewModel.ViewState.Loaded
        val community = state(
            guild.copy(features = setOf(GuildFeature.COMMUNITY)),
            canManage = true,
        ) as ServerSettingsModerationViewModel.ViewState.Loaded

        assertEquals(GuildVerificationLevel.HIGH, loaded.guild.verificationLevel)
        assertEquals(GuildExplicitContentFilter.SOME, loaded.guild.explicitContentFilter)
        assertFalse(loaded.isCommunity)
        assertTrue(community.isCommunity)
    }

    private fun state(guild: Guild?, canManage: Boolean) =
        ServerSettingsModerationViewModel.toViewState(
            ServerSettingsModerationViewModel.StoreState(guild, canManage),
        )
}
