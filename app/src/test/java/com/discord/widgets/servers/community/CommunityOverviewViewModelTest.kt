package com.discord.widgets.servers.community

import com.discord.api.guild.GuildFeature
import com.discord.api.premium.PremiumTier
import com.discord.api.user.NsfwAllowance
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class CommunityOverviewViewModelTest {

    @Test
    fun `owner can manage community without a permission snapshot`() {
        val guild = Guild(id = 1L, ownerId = 2L)
        val state = WidgetServerSettingsCommunityOverviewViewModel.StoreState.Valid(
            guild,
            me(2L),
            permissions = null,
            rulesChannel = null,
            updatesChannel = null,
        )

        assertEquals(
            WidgetServerSettingsCommunityOverviewViewModel.ViewState.Loaded(guild, null, null),
            WidgetServerSettingsCommunityOverviewViewModel.toViewState(state),
        )
    }

    @Test
    fun `non-owner without manage guild is rejected`() {
        val state = WidgetServerSettingsCommunityOverviewViewModel.StoreState.Valid(
            Guild(id = 1L, ownerId = 3L),
            me(2L),
            permissions = 0L,
            rulesChannel = null,
            updatesChannel = null,
        )

        assertSame(
            WidgetServerSettingsCommunityOverviewViewModel.ViewState.Invalid,
            WidgetServerSettingsCommunityOverviewViewModel.toViewState(state),
        )
    }

    @Test
    fun `disable drops community and discovery`() {
        val params = WidgetServerSettingsCommunityOverviewViewModel.disableCommunityParams(
            Guild(
                features = setOf(GuildFeature.COMMUNITY, GuildFeature.DISCOVERABLE, GuildFeature.NEWS),
            ),
        )

        assertEquals(listOf(GuildFeature.NEWS), params.features)
    }

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
