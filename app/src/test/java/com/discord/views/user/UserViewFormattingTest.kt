package com.discord.views.user

import com.discord.models.user.CoreUser
import org.junit.Assert.assertEquals
import com.discord.models.user.MeUser
import com.discord.api.user.NsfwAllowance
import org.junit.Test

class UserViewFormattingTest {
    @Test
    fun preservesLegacyDiscriminatorAndMediaProxySizing() {
        assertEquals("member", formatUsername(CoreUser(1, "member")))
        assertEquals("member#0042", formatUsername(CoreUser(1, "member", discriminator = 42)))
        assertEquals(80, mediaProxySize(72))
    }

    @Test
    fun preservesCompleteAndLocalAvatarUrls() {
        assertEquals("data:image/png;base64,avatar", avatarUrl(user("data:image/png;base64,avatar"), null))
        assertEquals("https://cdn.discordapp.com/archive.webp", avatarUrl(user("https://cdn.discordapp.com/archive.webp"), null))
    }

    private fun user(avatar: String) = MeUser(
        id = 1,
        username = "user",
        avatar = avatar,
        banner = null,
        isBot = false,
        isSystemUser = false,
        discriminator = 0,
        premiumTier = com.discord.api.premium.PremiumTier.NONE,
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
