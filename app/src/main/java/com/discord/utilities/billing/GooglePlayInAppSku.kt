package com.discord.utilities.billing

import com.android.billingclient.api.SkuDetails

enum class InAppSkuType {
    PREMIUM_GIFT,
}

data class GooglePlayInAppSku(
    val paymentGatewaySkuId: String,
    var type: InAppSkuType,
    var skuDetails: SkuDetails? = null,
    /**
     * The backing Discord SKU this product grants.
     *
     * **Null in this reconstruction, deliberately.** The reference hardcodes Discord's own SKU ids
     * here; those are credentials recovered from the reference APK and `PLAN.md` forbids reusing
     * them. They are also unusable: Play products are scoped to the publishing package, so under
     * `dev.mantikafasi.manticord` a purchase against Discord's SKU could never resolve.
     *
     * Populate from Manticord-owned configuration in Phase 13 via [InAppSkuConfig.installSkuIds].
     */
    val skuId: Long? = null,
)

/**
 * Maps this build's gift products onto Manticord-owned Discord SKU ids.
 *
 * Fails closed: with nothing installed every [GooglePlayInAppSku.skuId] stays null, so the gifting
 * flow can render and price its products but cannot complete a purchase against a SKU that is not
 * ours. `StoreGifting`/`StoreEntitlements` already treat a null SKU as "not purchasable".
 */
object InAppSkuConfig {

    @Volatile
    private var skuIdsByProductId: Map<String, Long> = emptyMap()

    /** `true` once Manticord-owned SKU ids have been supplied. */
    val isInstalled: Boolean get() = skuIdsByProductId.isNotEmpty()

    fun installSkuIds(skuIdsByProductId: Map<String, Long>) {
        this.skuIdsByProductId = skuIdsByProductId
    }

    fun clear() {
        skuIdsByProductId = emptyMap()
    }

    internal fun skuIdFor(paymentGatewaySkuId: String): Long? = skuIdsByProductId[paymentGatewaySkuId]
}

/**
 * The gift products this build offers.
 *
 * The product-id strings are the Play console identifiers and double as the key the gift artwork is
 * selected by (see `GiftingUtils.getGiftIcon`), which is why they are named constants rather than
 * configuration. Only the Discord SKU id is withheld — see [GooglePlayInAppSku.skuId].
 */
val premiumTier1Month = GooglePlayInAppSku(
    "premium_month_tier_1",
    InAppSkuType.PREMIUM_GIFT,
    skuId = InAppSkuConfig.skuIdFor("premium_month_tier_1"),
)
val premiumTier1Year = GooglePlayInAppSku(
    "premium_year_tier_1",
    InAppSkuType.PREMIUM_GIFT,
    skuId = InAppSkuConfig.skuIdFor("premium_year_tier_1"),
)
val premiumTier2Month = GooglePlayInAppSku(
    "premium_month_tier_2",
    InAppSkuType.PREMIUM_GIFT,
    skuId = InAppSkuConfig.skuIdFor("premium_month_tier_2"),
)
val premiumTier2Year = GooglePlayInAppSku(
    "premium_year_tier_2",
    InAppSkuType.PREMIUM_GIFT,
    skuId = InAppSkuConfig.skuIdFor("premium_year_tier_2"),
)
val premiumGifts = listOf(
    premiumTier1Month,
    premiumTier1Year,
    premiumTier2Month,
    premiumTier2Year,
)
