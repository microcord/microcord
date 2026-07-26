package com.discord.stores

/**
 * How much of a channel the user is looking at.
 *
 * Reported as `channel_view` on `channel_opened`. A peek is the transient preview a user gets from
 * a search result or a jump link, and the backend distinguishes it from a real open so previews do
 * not count as engagement. Derived from `ResolvedSelectedChannel`: a selection with a peek parent
 * is a [PEEK_VIEW], anything else is a [FULL_VIEW].
 */
enum class ChannelAnalyticsViewType(val analyticsValue: String) {
    FULL_VIEW("Full View"),
    PEEK_VIEW("Peek View"),
}
