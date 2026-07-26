package com.discord.utilities.premium

import com.discord.api.guild.GuildFeature
import com.discord.api.premium.PremiumTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumUtilsTest {

    @Test
    fun `boost feature tiers are cumulative`() {
        // A tier-1 feature is also present in the tier-2 and tier-3 sets, so the lookup must return
        // the *lowest* tier that unlocks it.
        assertEquals(1, PremiumUtils.getMinimumBoostTierForGuildFeature(GuildFeature.INVITE_SPLASH))
        assertEquals(1, PremiumUtils.getMinimumBoostTierForGuildFeature(GuildFeature.ANIMATED_ICON))
        assertEquals(2, PremiumUtils.getMinimumBoostTierForGuildFeature(GuildFeature.BANNER))
        assertEquals(2, PremiumUtils.getMinimumBoostTierForGuildFeature(GuildFeature.PRIVATE_THREADS))
        assertEquals(2, PremiumUtils.getMinimumBoostTierForGuildFeature(GuildFeature.ROLE_ICONS))
        assertEquals(3, PremiumUtils.getMinimumBoostTierForGuildFeature(GuildFeature.VANITY_URL))
        assertEquals(3, PremiumUtils.getMinimumBoostTierForGuildFeature(GuildFeature.ANIMATED_BANNER))
    }

    @Test
    fun `a feature boosting never unlocks has no minimum tier`() {
        assertNull(PremiumUtils.getMinimumBoostTierForGuildFeature(GuildFeature.PARTNERED))
        assertNull(PremiumUtils.getMinimumBoostTierForGuildFeature(GuildFeature.HUB))
    }

    @Test
    fun `guild upload ceiling steps at tier 2 and tier 3`() {
        assertEquals(8, PremiumUtils.getGuildMaxFileSizeMB(0))
        assertEquals(8, PremiumUtils.getGuildMaxFileSizeMB(1))
        assertEquals(50, PremiumUtils.getGuildMaxFileSizeMB(2))
        assertEquals(100, PremiumUtils.getGuildMaxFileSizeMB(3))
    }

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
