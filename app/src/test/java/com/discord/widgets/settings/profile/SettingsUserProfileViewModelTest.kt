package com.discord.widgets.settings.profile

import com.discord.api.premium.PremiumTier
import com.discord.api.user.NsfwAllowance
import com.discord.api.user.UserProfile
import com.discord.models.guild.Guild
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
                displayName = null,
                pronouns = "any/all",
                bio = "new bio",
                isEditingBio = true,
            ),
        )

        assertTrue(restored.currentAvatar is NullSerializable.Null)
        assertTrue(restored.currentBannerImage is NullSerializable.Value)
        assertTrue(restored.currentBannerColorHex is NullSerializable.Null)
        assertEquals("nick", restored.currentNickname)
        assertEquals("any/all", restored.currentPronouns)
        assertEquals("new bio", restored.currentBio)
        assertTrue(restored.isEditingBio)
    }

    @Test fun `identity and profile fields go to their own endpoints`() {
        val state = loaded(
            user = user(bio = "same", avatar = "avatar"),
            bio = "same",
            avatar = NullSerializable.Null(),
            banner = NullSerializable.Value("data:image/png;base64,banner"),
            color = NullSerializable.Value("#5865F2"),
        )

        val account = SettingsUserProfileViewModel.userPatch(state)
        val profile = SettingsUserProfileViewModel.profilePatch(state)

        // The avatar is an account field; the banner and accent colour are profile metadata.
        assertTrue(account?.avatar is NullSerializable.Null)
        assertNull(account?.globalName)
        assertTrue(profile?.banner is NullSerializable.Value)
        assertEquals(0x5865F2, profile?.accentColor?.value)
        assertNull(profile?.bio)
        assertNull(profile?.pronouns)
    }

    @Test fun `an untouched screen sends nothing`() {
        val state = loaded(user = user(bio = "same"), bio = "same")

        assertNull(SettingsUserProfileViewModel.userPatch(state))
        assertNull(SettingsUserProfileViewModel.profilePatch(state))
        assertNull(SettingsUserProfileViewModel.guildMemberPatch(state))
    }

    @Test fun `cleared bio and pronouns are sent as empty strings, not nulls`() {
        val state = loaded(user = user(bio = "old"), bio = "", pronouns = "")
            .copy(userProfile = profileWith(pronouns = "they/them"))

        val profile = SettingsUserProfileViewModel.profilePatch(state)

        assertEquals("", profile?.bio)
        assertEquals("", profile?.pronouns)
        assertNull(profile?.banner)
        assertNull(profile?.accentColor)
    }

    @Test fun `a guild profile never carries an accent colour`() {
        val state = loaded(
            user = user(),
            member = member(),
            pronouns = "any/all",
            color = NullSerializable.Value("#5865F2"),
        )

        val profile = SettingsUserProfileViewModel.profilePatch(state)

        assertEquals("any/all", profile?.pronouns)
        assertNull(profile?.accentColor)
    }

    @Test fun `a per-server bio needs nitro but a nickname and pronouns do not`() {
        val guild = Guild(id = 7)
        val free = loaded(user = user(), member = member(), pronouns = "any/all").copy(guild = guild)
        val nitro = loaded(
            user = user(premiumTier = PremiumTier.TIER_2),
            member = member(),
            pronouns = "any/all",
        ).copy(guild = guild)

        assertFalse(free.canEditGuildProfile)
        assertTrue(nitro.canEditGuildProfile)
        // The account-wide profile is never gated.
        assertTrue(loaded(user = user()).canEditGuildProfile)
    }

    @Test fun `changed pronouns make the screen dirty`() {
        val saved = loaded(user = user(), pronouns = "they/them")
            .copy(userProfile = profileWith(pronouns = "they/them"))

        assertFalse(saved.isDirty)
        assertTrue(saved.copy(currentPronouns = "she/her").isDirty)
        assertTrue(saved.copy(currentPronouns = "").isDirty)
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
                displayName = null,
                pronouns = null,
                bio = initial.currentBio,
                isEditingBio = false,
            ),
        )

        assertEquals(dataUrl, restored.currentAvatar?.value)
        assertEquals(dataUrl, SettingsUserProfileViewModel.userPatch(restored)?.avatar?.value)
        assertTrue(restored.showSaveFab)
    }

    @Test fun `guild save splits the member fields from the profile fields`() {
        val state = loaded(
            user = user(),
            member = member(nick = "old", bio = "old bio"),
            nickname = "new",
            bio = "new bio",
            avatar = NullSerializable.Null(),
            banner = NullSerializable.Value("data:image/png;base64,banner"),
        )

        val memberPatch = SettingsUserProfileViewModel.guildMemberPatch(state)
        val profile = SettingsUserProfileViewModel.profilePatch(state)

        assertEquals("new", memberPatch?.nick)
        assertTrue(memberPatch?.avatar is NullSerializable.Null)
        assertTrue(profile?.banner is NullSerializable.Value)
        assertEquals("new bio", profile?.bio)
    }

    private fun profileWith(pronouns: String) =
        UserProfile(userProfile = UserProfile.ProfileMetadata(pronouns = pronouns))

    private fun member(nick: String? = "guild nick", bio: String? = "guild bio") = GuildMember(
        color = 0,
        hoistRoleId = 0,
        roles = emptyList(),
        nick = nick,
        premiumSince = null,
        pending = false,
        joinedAt = null,
        guildId = 7,
        userId = 1,
        bio = bio,
    )

    private fun loaded(
        user: MeUser,
        member: GuildMember? = null,
        nickname: String? = member?.nick,
        bio: String? = member?.bio ?: user.bio,
        pronouns: String? = null,
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
        currentPronouns = pronouns,
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
