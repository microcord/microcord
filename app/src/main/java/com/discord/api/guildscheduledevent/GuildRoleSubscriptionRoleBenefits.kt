package com.discord.api.guildscheduledevent

import com.discord.api.guildrolesubscription.GuildRoleSubscriptionBenefit

/** The benefits attached to the role a subscription SKU grants. */
data class GuildRoleSubscriptionRoleBenefits(
    val skuId: Long,
    val benefits: List<GuildRoleSubscriptionBenefit>,
)
