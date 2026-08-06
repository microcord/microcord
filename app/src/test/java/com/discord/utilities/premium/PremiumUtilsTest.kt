package com.discord.utilities.premium

import com.discord.api.guild.GuildFeature
import com.discord.api.premium.PremiumTier
import com.discord.models.guild.Guild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumUtilsTest {

    @Test
    fun `a perk follows the guild's features, not its boost level`() {
        // Tierless boosting: a level 3 guild that never applied the powerup has not unlocked it.
        assertTrue(PremiumUtils.guildCanAccessFeature(guild(GuildFeature.ROLE_ICONS), GuildFeature.ROLE_ICONS))
        assertFalse(PremiumUtils.guildCanAccessFeature(guild(premiumTier = 3), GuildFeature.ROLE_ICONS))
        assertFalse(PremiumUtils.guildCanAccessFeature(null, GuildFeature.ROLE_ICONS))
    }

    @Test
    fun `guild upload ceiling is the largest size powerup applied`() {
        val mb = 1024L * 1024L
        assertEquals(10 * mb, PremiumUtils.getGuildMaxFileSize(guild(premiumTier = 3)))
        assertEquals(50 * mb, PremiumUtils.getGuildMaxFileSize(guild(GuildFeature.MAX_FILE_SIZE_50_MB)))
        assertEquals(250 * mb, PremiumUtils.getGuildMaxFileSize(guild(GuildFeature.MAX_FILE_SIZE_250_MB)))
        // Holding several at once takes the biggest rather than the last listed.
        assertEquals(
            250 * mb,
            PremiumUtils.getGuildMaxFileSize(
                guild(GuildFeature.MAX_FILE_SIZE_50_MB, GuildFeature.MAX_FILE_SIZE_250_MB),
            ),
        )
    }

    private fun guild(vararg features: GuildFeature, premiumTier: Int = 0) =
        Guild(id = 1, premiumTier = premiumTier, features = features.toSet())

    @Test
    fun `emoji slots grow with the tier`() {
        assertEquals(50, PremiumUtils.getGuildEmojiMaxCount(0, hasMoreEmoji = false))
        assertEquals(100, PremiumUtils.getGuildEmojiMaxCount(1, hasMoreEmoji = false))
        assertEquals(150, PremiumUtils.getGuildEmojiMaxCount(2, hasMoreEmoji = false))
        assertEquals(250, PremiumUtils.getGuildEmojiMaxCount(3, hasMoreEmoji = false))
    }

    @Test
    fun `MORE_EMOJI raises the floor to 200, but never lowers tier 3`() {
        assertEquals(200, PremiumUtils.getGuildEmojiMaxCount(0, hasMoreEmoji = true))
        assertEquals(200, PremiumUtils.getGuildEmojiMaxCount(2, hasMoreEmoji = true))
        assertEquals(250, PremiumUtils.getGuildEmojiMaxCount(3, hasMoreEmoji = true))
    }

    @Test
    fun `an unboosted guild has no sticker slots at all`() {
        assertEquals(0, PremiumUtils.getGuildStickerMaxCount(0, hasMoreSticker = false))
        assertEquals(15, PremiumUtils.getGuildStickerMaxCount(1, hasMoreSticker = false))
        assertEquals(30, PremiumUtils.getGuildStickerMaxCount(2, hasMoreSticker = false))
        assertEquals(60, PremiumUtils.getGuildStickerMaxCount(3, hasMoreSticker = false))
        assertEquals(60, PremiumUtils.getGuildStickerMaxCount(0, hasMoreSticker = true))
    }

    @Test
    fun `tier rank comparison follows the enum's entitlement order`() {
        assertTrue(PremiumUtils.isPremiumTierAtLeast(PremiumTier.TIER_2, PremiumTier.TIER_1))
        assertTrue(PremiumUtils.isPremiumTierAtLeast(PremiumTier.TIER_1, PremiumTier.TIER_1))
        assertFalse(PremiumUtils.isPremiumTierAtLeast(PremiumTier.NONE, PremiumTier.TIER_0))
    }

    @Test
    fun `micro amounts convert to minor currency units`() {
        assertEquals(999, PremiumUtils.microAmountToMinor(9_990_000L))
        assertEquals(0, PremiumUtils.microAmountToMinor(9_999L))
    }

    @Test
    fun `premium stickers are Nitro and Nitro Basic, not Nitro Classic`() {
        assertTrue(PremiumTier.TIER_2.grantsAccessToPremiumStickers())
        assertTrue(PremiumTier.TIER_0.grantsAccessToPremiumStickers())
        assertFalse(PremiumTier.TIER_1.grantsAccessToPremiumStickers())
        assertFalse(PremiumTier.NONE.grantsAccessToPremiumStickers())
    }

    @Test
    fun `custom tags and animated avatars are Nitro Classic and Nitro, not Nitro Basic`() {
        assertTrue(PremiumTier.TIER_1.grantsAccessToCustomTagAndAnimatedAvatarFeatures())
        assertTrue(PremiumTier.TIER_2.grantsAccessToCustomTagAndAnimatedAvatarFeatures())
        assertFalse(PremiumTier.TIER_0.grantsAccessToCustomTagAndAnimatedAvatarFeatures())
    }
}
