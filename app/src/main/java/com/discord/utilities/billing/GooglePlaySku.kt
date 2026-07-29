package com.discord.utilities.billing

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.premium.SubscriptionInterval

/** The 126.21 subscription products used to present Play plan choices. */
enum class GooglePlaySku(
    val skuName: String,
    @DrawableRes val iconDrawableResId: Int,
    val type: Type,
    val upgrade: GooglePlaySku? = null,
    val premiumSubscriptionCount: Int,
    val interval: SubscriptionInterval,
) {
    PREMIUM_TIER_2_YEARLY(
        "premium_tier_2_yearly", R.drawable.ic_plan_premium_tier_2,
        Type.PREMIUM_TIER_2, premiumSubscriptionCount = 2, interval = SubscriptionInterval.YEARLY,
    ),
    PREMIUM_TIER_2_MONTHLY(
        "premium_tier_2_monthly", R.drawable.ic_plan_premium_tier_2,
        Type.PREMIUM_TIER_2, PREMIUM_TIER_2_YEARLY, 2, SubscriptionInterval.MONTHLY,
    ),
    PREMIUM_TIER_1_YEARLY(
        "premium_tier_1_yearly", R.drawable.ic_plan_premium_tier_1,
        Type.PREMIUM_TIER_1, premiumSubscriptionCount = 0, interval = SubscriptionInterval.YEARLY,
    ),
    PREMIUM_TIER_1_MONTHLY(
        "premium_tier_1_monthly", R.drawable.ic_plan_premium_tier_1,
        Type.PREMIUM_TIER_1, PREMIUM_TIER_1_YEARLY, 0, SubscriptionInterval.MONTHLY,
    ),
    PREMIUM_TIER_2_PREMIUM_GUILD_1_YEARLY(
        "premium_tier_2_premium_guild_1_yearly", R.drawable.ic_plan_premium_and_premium_guild_1,
        Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD, premiumSubscriptionCount = 3, interval = SubscriptionInterval.YEARLY,
    ),
    PREMIUM_TIER_2_PREMIUM_GUILD_1_MONTHLY(
        "premium_tier_2_premium_guild_1_monthly", R.drawable.ic_plan_premium_and_premium_guild_1,
        Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD, PREMIUM_TIER_2_PREMIUM_GUILD_1_YEARLY, 3, SubscriptionInterval.MONTHLY,
    ),
    PREMIUM_TIER_2_PREMIUM_GUILD_2_YEARLY(
        "premium_tier_2_premium_guild_2_yearly", R.drawable.ic_plan_premium_and_premium_guild_2,
        Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD, premiumSubscriptionCount = 4, interval = SubscriptionInterval.YEARLY,
    ),
    PREMIUM_TIER_2_PREMIUM_GUILD_2_MONTHLY(
        "premium_tier_2_premium_guild_2_monthly", R.drawable.ic_plan_premium_and_premium_guild_2,
        Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD, PREMIUM_TIER_2_PREMIUM_GUILD_2_YEARLY, 4, SubscriptionInterval.MONTHLY,
    ),
    PREMIUM_TIER_2_PREMIUM_GUILD_3_YEARLY(
        "premium_tier_2_premium_guild_3_yearly", R.drawable.ic_plan_premium_and_premium_guild_3,
        Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD, premiumSubscriptionCount = 5, interval = SubscriptionInterval.YEARLY,
    ),
    PREMIUM_TIER_2_PREMIUM_GUILD_3_MONTHLY(
        "premium_tier_2_premium_guild_3_monthly", R.drawable.ic_plan_premium_and_premium_guild_3,
        Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD, PREMIUM_TIER_2_PREMIUM_GUILD_3_YEARLY, 5, SubscriptionInterval.MONTHLY,
    ),
    PREMIUM_TIER_2_PREMIUM_GUILD_5_YEARLY(
        "premium_tier_2_premium_guild_5_yearly", R.drawable.ic_plan_premium_and_premium_guild_5,
        Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD, premiumSubscriptionCount = 7, interval = SubscriptionInterval.YEARLY,
    ),
    PREMIUM_TIER_2_PREMIUM_GUILD_5_MONTHLY(
        "premium_tier_2_premium_guild_5_monthly", R.drawable.ic_plan_premium_and_premium_guild_5,
        Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD, PREMIUM_TIER_2_PREMIUM_GUILD_5_YEARLY, 7, SubscriptionInterval.MONTHLY,
    ),
    PREMIUM_TIER_2_PREMIUM_GUILD_10_MONTHLY(
        "premium_tier_2_premium_guild_10_monthly", R.drawable.ic_plan_premium_and_premium_guild_10,
        Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD, premiumSubscriptionCount = 12, interval = SubscriptionInterval.MONTHLY,
    ),
    PREMIUM_TIER_2_PREMIUM_GUILD_13_MONTHLY(
        "premium_tier_2_premium_guild_13_monthly", R.drawable.ic_plan_premium_and_premium_guild_13,
        Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD, premiumSubscriptionCount = 15, interval = SubscriptionInterval.MONTHLY,
    ),
    PREMIUM_TIER_2_PREMIUM_GUILD_28_MONTHLY(
        "premium_tier_2_premium_guild_28_monthly", R.drawable.ic_plan_premium_and_premium_guild_28,
        Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD, premiumSubscriptionCount = 30, interval = SubscriptionInterval.MONTHLY,
    ),
    PREMIUM_TIER_1_PREMIUM_GUILD_1_YEARLY(
        "premium_tier_1_premium_guild_1_yearly", R.drawable.ic_plan_premium_tier_1_and_premium_guild_1,
        Type.PREMIUM_TIER_1_AND_PREMIUM_GUILD, premiumSubscriptionCount = 1, interval = SubscriptionInterval.YEARLY,
    ),
    PREMIUM_TIER_1_PREMIUM_GUILD_1_MONTHLY(
        "premium_tier_1_premium_guild_1_monthly", R.drawable.ic_plan_premium_tier_1_and_premium_guild_1,
        Type.PREMIUM_TIER_1_AND_PREMIUM_GUILD, PREMIUM_TIER_1_PREMIUM_GUILD_1_YEARLY, 1, SubscriptionInterval.MONTHLY,
    ),
    PREMIUM_GUILD_1_MONTHLY(
        "premium_guild_1_monthly", R.drawable.ic_plan_premium_guild_1,
        Type.PREMIUM_GUILD, PREMIUM_TIER_2_MONTHLY, 1, SubscriptionInterval.MONTHLY,
    ),
    PREMIUM_GUILD_2_MONTHLY(
        "premium_guild_2_monthly", R.drawable.ic_plan_premium_guild_2,
        Type.PREMIUM_GUILD, PREMIUM_TIER_2_MONTHLY, 2, SubscriptionInterval.MONTHLY,
    );

    enum class Type {
        PREMIUM_TIER_2,
        PREMIUM_TIER_1,
        PREMIUM_TIER_2_AND_PREMIUM_GUILD,
        PREMIUM_TIER_1_AND_PREMIUM_GUILD,
        PREMIUM_GUILD,
    }

    enum class Section(@StringRes val headerResource: Int) {
        PREMIUM(R.string.billing_premium_plans),
        PREMIUM_AND_PREMIUM_GUILD(R.string.billing_premium_and_premium_guild_plans),
        PREMIUM_GUILD(R.string.billing_premium_guild_plans),
    }

    val section: Section
        get() = when (type) {
            Type.PREMIUM_TIER_2, Type.PREMIUM_TIER_1 -> Section.PREMIUM
            Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD,
            Type.PREMIUM_TIER_1_AND_PREMIUM_GUILD,
            -> Section.PREMIUM_AND_PREMIUM_GUILD
            Type.PREMIUM_GUILD -> Section.PREMIUM_GUILD
        }

    val isBundledSku: Boolean
        get() = type == Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD ||
            type == Type.PREMIUM_TIER_1_AND_PREMIUM_GUILD

    val isTier1: Boolean
        get() = type == Type.PREMIUM_TIER_1 || type == Type.PREMIUM_TIER_1_AND_PREMIUM_GUILD

    val isTier2: Boolean
        get() = type == Type.PREMIUM_TIER_2 || type == Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD

    companion object {
        private val bySkuName = entries.associateBy(GooglePlaySku::skuName)

        val ALL_SKU_NAMES: List<String> = entries.map(GooglePlaySku::skuName)

        fun fromSkuName(skuName: String): GooglePlaySku? = bySkuName[skuName]

        fun getDowngrade(skuName: String): GooglePlaySku? =
            entries.firstOrNull { it.upgrade?.skuName == skuName }

        @DrawableRes
        fun getBorderResource(skuType: Type): Int = when (skuType) {
            Type.PREMIUM_TIER_2 -> R.drawable.drawable_bg_nitro_gradient_diagonal
            Type.PREMIUM_TIER_1 -> R.drawable.drawable_bg_nitro_classic_gradient_diagonal
            Type.PREMIUM_TIER_2_AND_PREMIUM_GUILD -> R.drawable.drawable_bg_premium_tier_2_and_premium_guild_gradient
            Type.PREMIUM_TIER_1_AND_PREMIUM_GUILD -> R.drawable.drawable_bg_premium_tier_1_and_premium_guild_gradient
            Type.PREMIUM_GUILD -> R.drawable.drawable_bg_premium_guild_gradient
        }
    }
}
