package com.discord.utilities.intent

import android.net.Uri

enum class StaticChannelRoutes(val route: String) {
    ROLE_SUBSCRIPTIONS("role-subscriptions");

    data class WithGuild(val route: StaticChannelRoutes, val guildId: Long)

    companion object {
        private val routeToName = values().associateBy(StaticChannelRoutes::route)
        private val channelRoute = Regex("^/channels/([^/]+)/([^/]+)/?$")

        fun Uri.extractStaticRoute(): WithGuild? {
            if (host != null && !IntentUtils.isHttpDomainUrl(this)) return null
            val match = path?.let(channelRoute::matchEntire) ?: return null
            val route = routeToName[match.groupValues[2]] ?: return null
            return WithGuild(route, match.groupValues[1].toLongOrNull() ?: 0L)
        }
    }
}
