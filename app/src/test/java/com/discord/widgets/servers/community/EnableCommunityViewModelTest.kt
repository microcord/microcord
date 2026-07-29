package com.discord.widgets.servers.community

import com.discord.api.guild.GuildExplicitContentFilter
import com.discord.api.guild.GuildFeature
import com.discord.api.guild.GuildVerificationLevel
import com.discord.api.premium.PremiumTier
import com.discord.api.role.GuildRole
import com.discord.api.user.NsfwAllowance
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnableCommunityViewModelTest {

    @Test
    fun `owner receives reference setup defaults`() {
        val guild = Guild(id = 1L, ownerId = 2L)
        val state = WidgetServerSettingsEnableCommunityViewModel.toViewState(
            WidgetServerSettingsEnableCommunityViewModel.StoreState.Valid(
                guild = guild,
                me = me(2L),
                permissions = null,
                roles = null,
                rulesChannel = null,
                updatesChannel = null,
            ),
            guild.id,
        ) as WidgetServerSettingsEnableCommunityViewModel.ViewState.Loaded

        assertEquals(0, state.currentPage)
        assertEquals(CREATE_NEW_CHANNEL_CONSTANT, state.communityGuildConfig.rulesChannelId)
        assertEquals(CREATE_NEW_CHANNEL_CONSTANT, state.communityGuildConfig.updatesChannelId)
        assertFalse(state.communityGuildConfig.isPrivacyPolicyAccepted)
        assertFalse(state.communityGuildConfig.everyonePermissions)
    }

    @Test
    fun `existing requirements and restricted everyone role start selected`() {
        val guild = Guild(
            id = 1L,
            ownerId = 2L,
            defaultMessageNotifications = 1,
            verificationLevel = GuildVerificationLevel.MEDIUM,
            explicitContentFilter = GuildExplicitContentFilter.SOME,
            features = setOf(GuildFeature.NEWS),
        )
        val role = role(guild.id, WidgetServerSettingsEnableCommunityViewModel.COMMUNITY_EVERYONE_PERMISSIONS)
        val state = WidgetServerSettingsEnableCommunityViewModel.toViewState(
            WidgetServerSettingsEnableCommunityViewModel.StoreState.Valid(
                guild,
                me(2L),
                null,
                mapOf(role.id to role),
                null,
                null,
            ),
            guild.id,
        ) as WidgetServerSettingsEnableCommunityViewModel.ViewState.Loaded

        assertTrue(state.communityGuildConfig.defaultMessageNotifications)
        assertTrue(state.communityGuildConfig.verificationLevel)
        assertTrue(state.communityGuildConfig.explicitContentFilter)
        assertTrue(state.communityGuildConfig.everyonePermissions)
        assertEquals(listOf(GuildFeature.NEWS), state.communityGuildConfig.features)
    }

    @Test
    fun `guild update enables community requirements and selected channels`() {
        val params = WidgetServerSettingsEnableCommunityViewModel.updatedGuildParams(
            WidgetServerSettingsEnableCommunityViewModel.CommunityGuildConfig(
                rulesChannel = null,
                updatesChannel = null,
                rulesChannelId = 10L,
                updatesChannelId = 20L,
                isPrivacyPolicyAccepted = true,
                defaultMessageNotifications = true,
                verificationLevel = true,
                explicitContentFilter = true,
                guild = Guild(features = setOf(GuildFeature.NEWS)),
                everyonePermissions = false,
                features = listOf(GuildFeature.NEWS),
                roles = null,
            ),
        )

        assertEquals(1, params.defaultMessageNotifications)
        assertEquals(GuildVerificationLevel.LOW, params.verificationLevel)
        assertEquals(GuildExplicitContentFilter.ALL, params.explicitContentFilter)
        assertEquals(listOf(GuildFeature.NEWS, GuildFeature.COMMUNITY), params.features)
        assertEquals(10L, params.rulesChannelId)
        assertEquals(20L, params.publicUpdatesChannelId)
    }

    private fun role(id: Long, permissions: Long) = GuildRole(
        position = 0,
        name = "@everyone",
        id = id,
        color = 0,
        hoist = false,
        permissions = permissions,
        mentionable = false,
        managed = false,
        icon = null,
        tags = null,
        unicodeEmoji = null,
    )

    private fun me(id: Long) = MeUser(
        id = id,
        username = "owner",
        avatar = null,
        banner = null,
        isBot = false,
        isSystemUser = false,
        discriminator = 1,
        premiumTier = PremiumTier.NONE,
        email = null,
        mfaEnabled = false,
        isVerified = true,
        token = null,
        flags = 0,
        publicFlags = 0,
        phoneNumber = null,
        nsfwAllowance = NsfwAllowance.UNKNOWN,
        bio = null,
        bannerColor = null,
    )
}
