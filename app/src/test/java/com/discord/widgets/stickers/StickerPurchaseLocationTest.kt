package com.discord.widgets.stickers

import com.discord.api.premium.PremiumTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerPurchaseLocationTest {

    /** These strings are wire values sent to analytics, not copy — they must not be reworded. */
    @Test
    fun `analytics values are the reference wire strings in ordinal order`() {
        assertEquals(
            listOf("Expression Picker", "Sticker Popout", "Sticker Upsell Popout"),
            StickerPurchaseLocation.values().map { it.analyticsValue },
        )
    }

    @Test
    fun `a purchasable pack reports the upsell popout`() {
        assertEquals(
            StickerPurchaseLocation.STICKER_UPSELL_POPOUT,
            StickerPurchaseLocation.getPopoutPurchaseLocation(canStickerPackBePurchased = true),
        )
        assertEquals(
            StickerPurchaseLocation.STICKER_POPOUT,
            StickerPurchaseLocation.getPopoutPurchaseLocation(canStickerPackBePurchased = false),
        )
    }

    @Test
    fun `simplifying collapses both popouts and passes everything else through`() {
        assertEquals(
            StickerPurchaseLocation.STICKER_POPOUT,
            StickerPurchaseLocation.getSimplifiedLocation(StickerPurchaseLocation.STICKER_POPOUT),
        )
        assertEquals(
            StickerPurchaseLocation.STICKER_POPOUT,
            StickerPurchaseLocation.getSimplifiedLocation(
                StickerPurchaseLocation.STICKER_UPSELL_POPOUT,
            ),
        )
        assertEquals(
            StickerPurchaseLocation.EXPRESSION_PICKER,
            StickerPurchaseLocation.getSimplifiedLocation(StickerPurchaseLocation.EXPRESSION_PICKER),
        )
        assertNull(StickerPurchaseLocation.getSimplifiedLocation(null))
    }

    /** 299c list, 199c for Nitro — the popout advertises the difference as a percentage. */
    @Test
    fun `the advertised pack discount is 33 percent`() {
        assertEquals(33, StickerPackFormatting.calculatePremiumStickerPackDiscount())
    }

    @Test
    fun `a premium pack is free for the tiers that unlock premium stickers`() {
        val pack = premiumPack()
        assertTrue(StickerPackFormatting.isStickerPackFreeForPremiumTier(pack, PremiumTier.TIER_2))
        assertTrue(StickerPackFormatting.isStickerPackFreeForPremiumTier(pack, PremiumTier.TIER_0))
        assertFalse(StickerPackFormatting.isStickerPackFreeForPremiumTier(pack, PremiumTier.TIER_1))
        assertFalse(StickerPackFormatting.isStickerPackFreeForPremiumTier(pack, PremiumTier.NONE))
    }

    private fun premiumPack() = com.discord.models.sticker.dto.ModelStickerPack(
        id = 1L,
        stickers = emptyList(),
        name = "pack",
        storeListing = com.discord.models.sticker.dto.ModelStickerPackStoreListing(
            sku = com.discord.models.domain.ModelSku(
                applicationId = 0L,
                name = "sku",
                id = 1L,
                application = null,
                premium = true,
                type = 0,
                price = com.discord.models.domain.ModelSku.Price(amount = 299, currency = "USD"),
                flags = com.discord.models.domain.SKU_FLAG_AVAILABLE,
                externalSkuStrategies = null,
            ),
            id = 1L,
            description = "",
            unpublishedAt = null,
            thumbnail = null,
        ),
        skuId = 1L,
        coverStickerId = null,
        description = null,
        bannerAssetId = null,
    )
}
