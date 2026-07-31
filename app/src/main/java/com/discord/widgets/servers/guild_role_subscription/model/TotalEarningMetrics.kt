package com.discord.widgets.servers.guild_role_subscription.model

data class TotalEarningMetrics(
    val currentMonthEarningMetrics: CurrentMonthEarningMetrics,
    val currentPeriod: TotalPayoutsForPeriod?,
    val previousPeriods: List<TotalPayoutsForPeriod> = emptyList(),
)
