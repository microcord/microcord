package com.discord.models.domain

import com.discord.api.premium.SubscriptionPlan
import com.discord.api.user.User
import com.discord.utilities.time.TimeUtils

/**
 * A gift code and what it resolves to.
 *
 * [storeListing] is only populated once the code has been resolved against the store; until then
 * the gift is "incomplete" and the redeem sheet shows a spinner rather than a product.
 */
data class ModelGift(
    val skuId: Long,
    val redeemed: Boolean,
    val expiresAt: String?,
    val code: String,
    val uses: Int,
    val storeListing: ModelStoreListing?,
    val maxUses: Int,
    val user: User?,
    val subscriptionPlanId: Long?,
    val subscriptionPlan: SubscriptionPlan?,
    val giftStyle: Int?,
) {
    fun isComplete(): Boolean = storeListing != null

    fun isClaimedByMe(): Boolean = redeemed

    fun isExpired(nowMillis: Long): Boolean =
        expiresAt != null && nowMillis > TimeUtils.parseUTCDate(expiresAt)

    fun getExpiresDiff(nowMillis: Long): Long =
        if (expiresAt != null) TimeUtils.parseUTCDate(expiresAt) - nowMillis else 0L

    fun isNitroGift(): Boolean = storeListing?.sku?.skuCategory == ModelSku.SkuCategory.NITRO

    fun isNitroClassicGift(): Boolean =
        storeListing?.sku?.skuCategory == ModelSku.SkuCategory.NITRO_CLASSIC

    fun isAnyNitroGift(): Boolean = isNitroGift() || isNitroClassicGift()

    override fun toString(): String = "ModelGift(skuId=$skuId, redeemed=$redeemed, " +
        "expiresAt=$expiresAt, code=<redacted>, uses=$uses, storeListing=$storeListing, " +
        "maxUses=$maxUses, user=$user, subscriptionPlanId=$subscriptionPlanId, " +
        "subscriptionPlan=$subscriptionPlan, giftStyle=$giftStyle)"
}

/**
 * Proof that a user owns a SKU. [parentId] links a bundled entitlement back to the bundle it came
 * with, and [consumed] applies only to one-shot entitlements such as gifts.
 */
data class ModelEntitlement(
    val id: Long,
    val applicationId: Long,
    val type: Int,
    val userId: Long,
    val skuId: Long,
    val sku: ModelSku,
    val subscriptionPlan: SubscriptionPlan?,
    val parentId: Long?,
    val consumed: Boolean?,
    val promotionId: Long?,
)
