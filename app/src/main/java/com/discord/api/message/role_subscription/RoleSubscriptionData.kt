package com.discord.api.message.role_subscription

data class RoleSubscriptionData(
    val roleSubscriptionListingId: Long,
    val tierName: String,
    val totalMonthsSubscribed: Int,
    val isRenewal: Boolean,
)
