package com.discord.api.guildrolesubscription

/**
 * A guild's subscription storefront: the set of tiers it sells.
 *
 * [subscriptionListingsIds] and [subscriptionListings] carry the same tiers; the server sends ids
 * only on the partial payloads and the full objects on the detail fetch.
 */
data class GuildRoleSubscriptionGroupListing(
    val id: Long,
    val guildId: Long,
    val applicationId: Long,
    val imageAsset: ImageAsset?,
    val description: String?,
    val subscriptionListingsIds: List<Long>?,
    val subscriptionListings: List<GuildRoleSubscriptionTierListing>?,
    val fullServerGate: Boolean,
)
