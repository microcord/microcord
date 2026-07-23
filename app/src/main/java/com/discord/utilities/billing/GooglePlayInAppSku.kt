package com.discord.utilities.billing

import com.android.billingclient.api.SkuDetails

enum class InAppSkuType {
    PREMIUM_GIFT,
}

data class GooglePlayInAppSku(
    val paymentGatewaySkuId: String,
    var type: InAppSkuType,
    var skuDetails: SkuDetails? = null,
    val skuId: Long? = null,
)

val premiumTier1Month = GooglePlayInAppSku(
    "premium_month_tier_1",
    InAppSkuType.PREMIUM_GIFT,
    skuId = 521846918637420545L,
)
val premiumTier1Year = GooglePlayInAppSku(
    "premium_year_tier_1",
    InAppSkuType.PREMIUM_GIFT,
    skuId = 521846918637420545L,
)
val premiumTier2Month = GooglePlayInAppSku(
    "premium_month_tier_2",
    InAppSkuType.PREMIUM_GIFT,
    skuId = 521847234246082599L,
)
val premiumTier2Year = GooglePlayInAppSku(
    "premium_year_tier_2",
    InAppSkuType.PREMIUM_GIFT,
    skuId = 521847234246082599L,
)
val premiumGifts = listOf(
    premiumTier1Month,
    premiumTier1Year,
    premiumTier2Month,
    premiumTier2Year,
)
