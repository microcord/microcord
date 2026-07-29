package com.discord.widgets.settings.profile

import com.discord.api.premium.PremiumTier
import com.discord.api.user.NsfwAllowance
import com.discord.api.user.UserProfile
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.nullserializable.NullSerializable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUserProfileViewModelTest {
    @Test fun `unchanged global profile is clean`() {
        val state = loaded(user = user(bio = "hello", avatar = "avatar"), bio = "hello")

        assertFalse(state.isDirty)
        assertFalse(state.showSaveFab)
        assertTrue(state.hasAvatarForDisplay)
        assertFalse(state.showBioEditor)
    }

    @Test fun `explicit clears stay dirty and remove previews`() {
        val state = loaded(
            user = user(bio = "hello", avatar = "avatar", banner = "banner"),
            bio = "hello",
            avatar = NullSerializable.Null(),
            banner = NullSerializable.Null(),
            color = NullSerializable.Null(),
        )

        assertTrue(state.isDirty)
        assertTrue(state.showSaveFab)
        assertFalse(state.hasAvatarForDisplay)
        assertFalse(state.hasBannerImageForDisplay)
        assertNull(state.nonDefaultColorPreviewHex)
    }

    @Test fun `guild nickname and bio compare against member`() {
        val member = GuildMember(
            color = 0,
            hoistRoleId = 0,
            roles = emptyList(),
            nick = "guild nick",
            premiumSince = null,
            pending = false,
            joinedAt = null,
            guildId = 7,
            userId = 1,
            bio = "guild bio",
        )
        val clean = loaded(user(), member, "guild nick", "guild bio")
        val changed = loaded(user(), member, "new nick", "guild bio")

        assertFalse(clean.isDirty)
        assertTrue(changed.isDirty)
    }

    private fun loaded(
        user: MeUser,
        member: GuildMember? = null,
        nickname: String? = member?.nick,
        bio: String? = member?.bio ?: user.bio,
        avatar: NullSerializable<String>? = null,
        banner: NullSerializable<String>? = null,
        color: NullSerializable<String>? = null,
    ) = SettingsUserProfileViewModel.ViewState.Loaded(
        user = user,
        guild = null,
        meMember = member,
        userProfile = UserProfile(),
        currentAvatar = avatar,
        currentBannerImage = banner,
        currentBannerColorHex = color,
        currentNickname = nickname,
        currentBio = bio,
        bioAst = emptyList(),
        isEditingBio = false,
        canEditNickname = true,
    )

    private fun user(
        bio: String? = "main bio",
        avatar: String? = null,
        banner: String? = null,
    ) = MeUser(
        id = 1,
        username = "user",
        avatar = avatar,
        banner = banner,
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
        bio = bio,
        bannerColor = "#123456",
    )
}
