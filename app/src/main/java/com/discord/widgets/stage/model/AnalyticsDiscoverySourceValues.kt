package com.discord.widgets.stage.model

/**
 * `source` values reported with stage-discovery analytics — which surface the user came from when
 * they opened a stage.
 */
object AnalyticsDiscoverySourceValues {
    const val UNSPECIFIED = 0
    const val TRENDING = 1
    const val PERSONALIZED = 2
    const val USER_GUILDS = 3
    const val RICH_PRESENCE = 4
    const val INTRO_CARD = 5
    const val FEATURED = 6
}
