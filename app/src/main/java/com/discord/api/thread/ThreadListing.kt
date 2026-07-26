package com.discord.api.thread

import com.discord.api.channel.Channel
import com.discord.api.message.Message

/** A page of threads returned by the thread-browsing and forum-listing endpoints. */
data class ThreadListing(
    val threads: List<Channel>,
    val hasMore: Boolean,
    val members: List<ThreadMember>,
    val firstMessages: List<Message>?,
)
