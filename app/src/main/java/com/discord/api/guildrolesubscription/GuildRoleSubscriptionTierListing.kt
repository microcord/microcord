package com.discord.api.guildrolesubscription

import com.discord.api.guildscheduledevent.GuildRoleSubscriptionRoleBenefits
import com.discord.api.premium.SubscriptionPlan

/** One purchasable tier of a guild's subscription storefront. */
data class GuildRoleSubscriptionTierListing(
    val id: Long,
    val applicationId: Long,
    val name: String,
    val published: Boolean,
    val description: String?,
    val imageAsset: ImageAsset?,
    val subscriptionPlans: List<SubscriptionPlan>,
    val roleBenefits: GuildRoleSubscriptionRoleBenefits,
    val roleId: Long,
)
