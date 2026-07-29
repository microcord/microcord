package com.discord.widgets.user.profile

import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.api.premium.PremiumTier
import com.discord.api.user.NsfwAllowance
import com.discord.api.user.UserProfile
import com.discord.api.voice.state.StageRequestToSpeakState
import com.discord.models.member.GuildMember
import com.discord.models.domain.emoji.toCodePoints
import com.discord.models.user.CoreUser
import com.discord.models.user.MeUser
import com.discord.nullserializable.NullSerializable
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

    @Test
    fun profileHeaderStateKeepsDerivedAvatarAndReducedMotionState() {
        val user = CoreUser(id = 2, username = "target", avatar = "avatar")
        val member = GuildMember(
            color = 0,
            hoistRoleId = 0,
            roles = emptyList(),
            nick = "guild nick",
            premiumSince = null,
            pending = false,
            joinedAt = null,
            guildId = 10,
            userId = user.id,
            avatarHash = "guild-avatar",
        )
        val profile = UserProfile(
            user = com.discord.api.user.User(
                id = user.id,
                username = user.username,
                banner = NullSerializable.Value("banner"),
                bannerColor = NullSerializable.Value("#123456"),
            ),
        )
        val storeState = UserProfileHeaderViewModel.StoreState(
            me = meUser(),
            user = user,
            userIdToGuildMemberMap = mapOf(user.id to member),
            guildMembers = emptyList(),
            richPresence = null,
            streamContext = null,
            userProfile = profile,
            allowAnimatedEmojis = true,
            reducedMotionEnabled = true,
            channel = null,
        )

        val initial = UserProfileHeaderViewModel.mapStoreState(storeState, null)
        val rebuilt = UserProfileHeaderViewModel.mapStoreState(
            storeState,
            initial.copy(allowAnimationInReducedMotion = true),
        )

        assertTrue(rebuilt.hasGuildMemberAvatar)
        assertTrue(rebuilt.showSmallAvatar)
        assertFalse(rebuilt.showMediumAvatar)
        assertEquals("banner", rebuilt.banner)
        assertEquals("#123456", rebuilt.bannerColorHex)
        assertTrue(rebuilt.allowAnimationInReducedMotion)
    }

    private fun meUser() = MeUser(
        1, "me", null, null, false, false, 1, PremiumTier.TIER_1,
        null, false, true, null, 0, 0, null, NsfwAllowance.UNKNOWN, null, null,
    )
}
