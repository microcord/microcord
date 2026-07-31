package com.discord.widgets.servers.guild_role_subscription.model

import com.discord.api.guildrolesubscription.PayoutGroup

data class TotalPayoutsForPeriod(
    val periodStartingAt: String,
    val subscriberCount: Long = 0,
    val revenue: Long = 0,
    val payoutGroups: Map<Long, PayoutGroup>? = null,
)
