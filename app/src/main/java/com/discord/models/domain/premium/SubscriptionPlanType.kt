package com.discord.models.domain.premium

import com.discord.api.premium.PremiumTier
import com.discord.api.premium.SubscriptionInterval
import com.discord.models.domain.ModelSubscription

/**
 * The fixed catalogue of Nitro and guild-boost plans this build knows about.
 *
 * Plan ids are server-assigned snowflakes baked into the client so the paywall can price a plan
 * before any store call returns. The `NONE_*` entries are the boost-only plans: they carry no
 * Nitro tier of their own, only the boost slots that come with them, which is why their
 * [premiumTier] is [PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY] and their [price] is zero.
 */
enum class SubscriptionPlanType(
    val price: Int,
    val interval: SubscriptionInterval,
    val intervalCount: Int,
    val planTypeString: String?,
    val planId: Long,
    val premiumTier: PremiumTier?,
) {
    NONE_MONTH(
        0, SubscriptionInterval.MONTHLY, 1, "none_month",
        628379151761408000L, PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY,
    ),
    NONE_3_MONTH(
        0, SubscriptionInterval.MONTHLY, 3, "none_3_month",
        944265614527037440L, PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY,
    ),
    NONE_6_MONTH(
        0, SubscriptionInterval.MONTHLY, 6, "none_6_month",
        944265636643602432L, PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY,
    ),
    NONE_YEAR(
        0, SubscriptionInterval.YEARLY, 1, "none_year",
        628381571568631808L, PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY,
    ),
    PREMIUM_MONTH_LEGACY(
        499, SubscriptionInterval.MONTHLY, 1, "premium_month",
        511651856145973248L, PremiumTier.TIER_2,
    ),
    PREMIUM_YEAR_LEGACY(
        4999, SubscriptionInterval.YEARLY, 1, "premium_year",
        511651860671627264L, PremiumTier.TIER_2,
    ),
    PREMIUM_MONTH_TIER_1(
        499, SubscriptionInterval.MONTHLY, 1, "premium_month_tier_1",
        511651871736201216L, PremiumTier.TIER_1,
    ),
    PREMIUM_YEAR_TIER_1(
        4999, SubscriptionInterval.YEARLY, 1, "premium_year_tier_1",
        511651876987469824L, PremiumTier.TIER_1,
    ),
    PREMIUM_MONTH_TIER_2(
        999, SubscriptionInterval.MONTHLY, 1, "premium_month_tier_2",
        511651880837840896L, PremiumTier.TIER_2,
    ),
    PREMIUM_3_MONTH_TIER_2(
        2849, SubscriptionInterval.MONTHLY, 3, "premium_3_month_tier_2",
        642251038925127690L, PremiumTier.TIER_2,
    ),
    PREMIUM_6_MONTH_TIER_2(
        5499, SubscriptionInterval.MONTHLY, 6, "premium_6_month_tier_2",
        944037208325619722L, PremiumTier.TIER_2,
    ),
    PREMIUM_YEAR_TIER_2(
        9999, SubscriptionInterval.YEARLY, 1, "premium_year_tier_2",
        511651885459963904L, PremiumTier.TIER_2,
    ),
    PREMIUM_MONTH_TIER_0(
        299, SubscriptionInterval.MONTHLY, 1, "premium_month_tier_0",
        978380692553465866L, PremiumTier.TIER_0,
    ),
    PREMIUM_GUILD_MONTH(499, SubscriptionInterval.MONTHLY, 1, null, 590665532894740483L, null),
    PREMIUM_GUILD_3_MONTH(1427, SubscriptionInterval.MONTHLY, 3, null, 944037355453415424L, null),
    PREMIUM_GUILD_6_MONTH(2750, SubscriptionInterval.MONTHLY, 6, null, 944037391444738048L, null),
    PREMIUM_GUILD_YEAR(4999, SubscriptionInterval.YEARLY, 1, null, 590665538238152709L, null);

    fun isMonthlyInterval(): Boolean = interval == SubscriptionInterval.MONTHLY

    /** A plan that is no longer sold but is still honoured for users who bought it. */
    fun isGrandfathered(): Boolean = this in LEGACY_PLANS

    fun isPremiumSubscription(): Boolean = this in PREMIUM_PLANS

    companion object {
        private val TIER_0_PLANS = setOf(PREMIUM_MONTH_TIER_0)
        private val TIER_1_PLANS = setOf(PREMIUM_YEAR_TIER_1, PREMIUM_MONTH_TIER_1)

        val LEGACY_PLANS = setOf(PREMIUM_YEAR_LEGACY, PREMIUM_MONTH_LEGACY)

        private val TIER_2_PLANS = setOf(
            PREMIUM_MONTH_TIER_2,
            PREMIUM_3_MONTH_TIER_2,
            PREMIUM_6_MONTH_TIER_2,
            PREMIUM_YEAR_TIER_2,
        ) + LEGACY_PLANS

        val PREMIUM_PLANS = TIER_0_PLANS + TIER_1_PLANS + TIER_2_PLANS

        val PREMIUM_GUILD_PLANS = setOf(
            PREMIUM_GUILD_MONTH,
            PREMIUM_3_MONTH_TIER_2,
            PREMIUM_6_MONTH_TIER_2,
            PREMIUM_GUILD_YEAR,
        )

        fun from(planId: Long): SubscriptionPlanType = entries.firstOrNull { it.planId == planId }
            ?: throw IllegalArgumentException("unsupported type plan id: $planId")

        fun from(planTypeString: String): SubscriptionPlanType =
            entries.firstOrNull { it.planTypeString == planTypeString }
                ?: throw IllegalArgumentException("unsupported planTypeString: $planTypeString")

        /**
         * Picks the plan that drives the subscription's tier: the first item that carries a
         * [premiumTier], falling back to the boost-only plan matching the billing period.
         */
        fun getBasePlanFromSubscriptionItems(
            items: List<ModelSubscription.SubscriptionItem>,
            interval: SubscriptionInterval,
            intervalCount: Int,
        ): SubscriptionPlanType {
            val plans = items.map { from(it.planId) }
            val resolvedInterval = plans.firstOrNull()?.interval ?: interval
            return plans.firstOrNull { it.premiumTier != null }
                ?: getNonePlanForIntervalType(resolvedInterval, intervalCount)
        }

        private fun getNonePlanForIntervalType(
            interval: SubscriptionInterval,
            intervalCount: Int,
        ): SubscriptionPlanType = when (interval) {
            SubscriptionInterval.MONTHLY -> when (intervalCount) {
                3 -> NONE_3_MONTH
                6 -> NONE_6_MONTH
                else -> NONE_MONTH
            }
            SubscriptionInterval.YEARLY -> NONE_YEAR
        }
    }
}
