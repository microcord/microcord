package com.discord.api.discovery

import com.discord.api.guild.GuildFeature

/**
 * A server as it appears in discovery. Distinct from a guild: the client never joins one of these,
 * it lurks, and the counts are the only membership information it gets.
 */
data class DiscoverableGuild(
    val id: Long,
    val name: String,
    val description: String? = null,
    val icon: String? = null,
    val splash: String? = null,
    val banner: String? = null,
    val discoverySplash: String? = null,
    val preferredLocale: String? = null,
    val features: List<GuildFeature?> = emptyList(),
    val approximatePresenceCount: Int? = null,
    val approximateMemberCount: Int? = null,
    val premiumSubscriptionCount: Int? = null,
    val primaryCategoryId: Int? = null,
    val keywords: List<String>? = null,
)

data class DiscoverableGuildsResponse(
    val guilds: List<DiscoverableGuild> = emptyList(),
    val offset: Int = 0,
    val limit: Int = 0,
    val total: Int = 0,
    /** Only present with `with_counts`; category `-1` carries the total across all of them. */
    val categories: List<DiscoveryCategoryCount>? = null,
)

/** [id] arrives as a string, unlike the integer ids everywhere else discovery uses them. */
data class DiscoveryCategoryCount(
    val id: String,
    val count: Int,
)

/** `is_primary` distinguishes a category a server may claim as its own from a browsing bucket. */
data class DiscoveryCategory(
    val id: Int,
    val name: String,
    val isPrimary: Boolean = false,
)
