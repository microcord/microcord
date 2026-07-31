package com.discord.widgets.servers.guild_role_subscription.model

data class CurrentMonthEarningMetrics(
    val revenue: Long = 0,
    val monthOverMonthRevenueChangePercent: Long? = null,
    val subscriberCount: Long = 0,
    val monthOverMonthSubscriberCountChange: Long? = null,
    val nextPaymentDate: String? = "",
    val revenueSinceDate: String? = "",
)
