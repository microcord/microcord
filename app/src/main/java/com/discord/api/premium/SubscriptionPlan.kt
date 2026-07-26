package com.discord.api.premium

import com.google.gson.annotations.SerializedName

/** The billing period of a [SubscriptionPlan]. */
enum class SubscriptionInterval {
    @SerializedName("1") MONTHLY,
    @SerializedName("2") YEARLY,
}

/** One purchasable plan of a subscription SKU. */
data class SubscriptionPlan(
    val id: Long,
    val intervalCount: Int,
    val interval: SubscriptionInterval,
    private val name: String,
    val price: Int,
)
