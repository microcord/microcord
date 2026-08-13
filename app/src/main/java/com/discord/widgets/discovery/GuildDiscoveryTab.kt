package com.discord.widgets.discovery

import androidx.annotation.StringRes
import com.discord.R

/**
 * The fixed subtabs of discovery. Each is a category id the server already understands, so the
 * negative ids are the reference's own pseudo-categories rather than anything we invented.
 */
enum class GuildDiscoveryTab(
    val categoryId: Int,
    @StringRes val tabTitle: Int,
    @StringRes val bannerTitle: Int,
    @StringRes val bannerDescription: Int,
    @StringRes val sectionTitle: Int?,
) {
    FEATURED(
        -1,
        R.string.global_discovery_servers_featured_category,
        R.string.guild_discovery_home_title,
        R.string.guild_discovery_home_subtitle,
        R.string.global_discovery_servers_popular_featured,
    ),
    GAMING(
        1,
        R.string.global_discovery_servers_gaming_category,
        R.string.global_discovery_servers_gaming_category,
        R.string.global_discovery_servers_gaming_description,
        R.string.global_discovery_servers_popular_gaming,
    ),
    MUSIC(
        2,
        R.string.global_discovery_servers_music_category,
        R.string.global_discovery_servers_music_category,
        R.string.global_discovery_servers_music_description,
        R.string.global_discovery_servers_popular_music,
    ),
    ENTERTAINMENT(
        3,
        R.string.global_discovery_servers_entertainment_category,
        R.string.global_discovery_servers_entertainment_category,
        R.string.global_discovery_servers_entertainment_description,
        R.string.global_discovery_servers_popular_entertainment,
    ),
    TECH(
        5,
        R.string.global_discovery_servers_tech_category,
        R.string.global_discovery_servers_tech_category,
        R.string.global_discovery_servers_tech_description,
        R.string.global_discovery_servers_popular_tech,
    ),
    EDUCATION(
        6,
        R.string.global_discovery_servers_education_category,
        R.string.global_discovery_servers_education_category,
        R.string.global_discovery_servers_education_description,
        R.string.global_discovery_servers_popular_education,
    ),
    HUBS(
        -2,
        R.string.global_discovery_servers_hubs_category,
        R.string.global_discovery_servers_hub_title,
        R.string.global_discovery_servers_hub_description,
        null,
    ),
    ;

    /** Hubs are not discoverable servers, so there is nothing to search there. */
    val isSearchable get() = this != HUBS

    companion object {
        const val ALL_CATEGORIES_ID = -1
        const val HUBS_CATEGORY_ID = -2
    }
}
