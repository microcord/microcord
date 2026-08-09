package com.discord.api.channel

import com.discord.api.message.Message

/**
 * One entry of `GET /channels/{id}/messages/pins`.
 *
 * [pinnedAt] stays the raw ISO8601 string rather than a parsed instant: it is echoed back verbatim
 * as the next page's `before` cursor, and reformatting it risks losing sub-second precision the
 * server uses to break ties.
 */
data class ChannelPin(
    val pinnedAt: String,
    val message: Message,
)

/**
 * `GET /channels/{id}/messages/pins`.
 *
 * Replaces the deprecated `GET /channels/{id}/pins`, which returned a bare array capped at 50 and
 * could not be paged past it. Ordered newest-pinned first; [hasMore] is the only end-of-list
 * signal, since a short page does not imply the end.
 */
data class ChannelPinsResponse(
    val items: List<ChannelPin> = emptyList(),
    val hasMore: Boolean = false,
)
