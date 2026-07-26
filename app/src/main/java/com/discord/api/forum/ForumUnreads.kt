package com.discord.api.forum

/** Unread state for one forum thread. [missing] marks a thread the server could not resolve. */
data class ForumUnread(
    val threadId: Long,
    val count: Int?,
    val missing: Boolean,
)

/** Payload of `FORUM_UNREADS`, the response to an outgoing forum unread request. */
data class ForumUnreads(
    val channelId: Long,
    val permissionDenied: Boolean,
    val threads: List<ForumUnread>,
)
