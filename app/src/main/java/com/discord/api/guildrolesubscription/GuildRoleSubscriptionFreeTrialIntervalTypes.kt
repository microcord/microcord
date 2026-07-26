package com.discord.api.guildrolesubscription

import com.google.gson.annotations.SerializedName

/** The billing period a subscription free trial is measured in. */
enum class GuildRoleSubscriptionFreeTrialIntervalTypes {
    @SerializedName("1") MONTHLY,
    @SerializedName("2") YEARLY,
    @SerializedName("3") DAILY,
}
