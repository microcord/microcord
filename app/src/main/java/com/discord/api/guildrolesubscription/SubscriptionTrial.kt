package com.discord.api.guildrolesubscription

/** A free-trial offer attached to a subscription SKU. */
data class SubscriptionTrial(
    val id: Long,
    val interval: GuildRoleSubscriptionFreeTrialIntervalTypes,
    val intervalCount: Int,
    val skuId: Long,
)

/** The length of a free trial, without the SKU it belongs to. */
data class SubscriptionTrialInterval(
    val interval: GuildRoleSubscriptionFreeTrialIntervalTypes,
    val intervalCount: Int,
)

/** How many free trials a tier has left, and the trial being offered. */
data class GuildRoleSubscriptionTierFreeTrial(
    val id: Long,
    val numActiveTrialUsers: Int?,
    val activeTrial: SubscriptionTrial?,
    val maxNumActiveTrialUsers: Int?,
)
