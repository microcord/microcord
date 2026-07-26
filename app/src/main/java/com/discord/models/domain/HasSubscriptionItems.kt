package com.discord.models.domain

import com.discord.api.premium.SubscriptionInterval
import com.discord.models.domain.premium.SubscriptionPlanType

/**
 * Shared reading of a subscription's line items.
 *
 * A Nitro subscription bundles one base plan with any number of guild-boost plans in the same
 * item list; this splits the two so the billing screens can price them separately.
 */
abstract class HasSubscriptionItems {

    abstract val items: List<ModelSubscription.SubscriptionItem>

    /** The plan id that determines the subscription's Nitro tier and billing period. */
    val premiumBasePlanId: Long
        get() {
            val firstPlan = items.firstOrNull()?.planId?.let(SubscriptionPlanType::from)
            return SubscriptionPlanType.getBasePlanFromSubscriptionItems(
                items,
                firstPlan?.interval ?: SubscriptionInterval.MONTHLY,
                firstPlan?.intervalCount ?: 1,
            ).planId
        }

    /** Everything that is not the base plan, i.e. the purchased guild-boost slots. */
    val premiumAdditionalPlans: List<ModelSubscription.SubscriptionAdditionalPlan>
        get() = items.filter { it.planId != premiumBasePlanId }
            .map { ModelSubscription.SubscriptionAdditionalPlan(it.planId, it.quantity) }

    val guildBoostPlanId: Long?
        get() = premiumAdditionalPlans.firstOrNull()?.planId
}
