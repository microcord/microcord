package com.discord.widgets.settings.profile

import com.discord.api.premium.PremiumTier
import com.discord.api.user.NsfwAllowance
import com.discord.api.user.UserProfile
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.nullserializable.NullSerializable
import org.junit.Assert.assertEquals
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

    @Test fun `missing global bio is clean`() {
        assertFalse(loaded(user = user(bio = null), bio = null).isDirty)
    }

    @Test fun `animated avatars require premium access`() {
        assertFalse(loaded(user = user(premiumTier = PremiumTier.NONE)).canHaveAnimatedAvatars)
        assertTrue(loaded(user = user(premiumTier = PremiumTier.TIER_1)).canHaveAnimatedAvatars)
        assertTrue(loaded(user = user(premiumTier = PremiumTier.TIER_2)).canHaveAnimatedAvatars)
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

    @Test fun `restored draft preserves explicit clears and editor state`() {
        val restored = SettingsUserProfileViewModel.restoreDraft(
            loaded(user(bio = "old", avatar = "avatar"), bio = "old"),
            SettingsUserProfileViewModel.Draft(
                avatar = NullSerializable.Null(),
                banner = NullSerializable.Value("data:image/png;base64,banner"),
                bannerColor = NullSerializable.Null(),
                nickname = "nick",
                bio = "new bio",
                isEditingBio = true,
            ),
        )

        assertTrue(restored.currentAvatar is NullSerializable.Null)
        assertTrue(restored.currentBannerImage is NullSerializable.Value)
        assertTrue(restored.currentBannerColorHex is NullSerializable.Null)
        assertEquals("nick", restored.currentNickname)
        assertEquals("new bio", restored.currentBio)
        assertTrue(restored.isEditingBio)
    }

    @Test fun `global save sends only changed fields and explicit clears`() {
        val state = loaded(
            user = user(bio = "same", avatar = "avatar"),
            bio = "same",
            avatar = NullSerializable.Null(),
            banner = NullSerializable.Value("data:image/png;base64,banner"),
            color = NullSerializable.Null(),
        )

        val patch = SettingsUserProfileViewModel.userPatch(state)

        assertTrue(patch.avatar is NullSerializable.Null)
        assertTrue(patch.banner is NullSerializable.Value)
        assertTrue(patch.bannerColor is NullSerializable.Null)
        assertNull(patch.bio)
    }

    @Test fun `cropped avatar preview survives draft restore and is saved`() {
        val dataUrl = "data:image/png;base64,YXZhdGFy"
        val initial = loaded(user = user(), avatar = NullSerializable.Value(dataUrl))
        val restored = SettingsUserProfileViewModel.restoreDraft(
            loaded(user = user()),
            SettingsUserProfileViewModel.Draft(
                avatar = initial.currentAvatar,
                banner = null,
                bannerColor = null,
                nickname = null,
                bio = initial.currentBio,
                isEditingBio = false,
            ),
        )

        assertEquals(dataUrl, restored.currentAvatar?.value)
        assertEquals(dataUrl, SettingsUserProfileViewModel.userPatch(restored).avatar?.value)
        assertTrue(restored.showSaveFab)
    }

    @Test fun `guild save includes nickname avatar banner and bio`() {
        val state = loaded(
            user = user(),
            member = GuildMember(
                color = 0,
                hoistRoleId = 0,
                roles = emptyList(),
                nick = "old",
                premiumSince = null,
                pending = false,
                joinedAt = null,
                guildId = 7,
                userId = 1,
                bio = "old bio",
            ),
            nickname = "new",
            bio = "new bio",
            avatar = NullSerializable.Null(),
            banner = NullSerializable.Value("data:image/png;base64,banner"),
        )

        val patch = SettingsUserProfileViewModel.guildMemberPatch(state)

        assertEquals("new", patch.nick)
        assertTrue(patch.avatar is NullSerializable.Null)
        assertTrue(patch.banner is NullSerializable.Value)
        assertEquals("new bio", patch.bio)
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
        premiumTier: PremiumTier = PremiumTier.NONE,
    ) = MeUser(
        id = 1,
        username = "user",
        avatar = avatar,
        banner = banner,
        isBot = false,
        isSystemUser = false,
        discriminator = 1,
        premiumTier = premiumTier,
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
