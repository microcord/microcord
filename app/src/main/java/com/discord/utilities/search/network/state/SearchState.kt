package com.discord.utilities.search.network.state

import com.discord.api.channel.Channel
import com.discord.api.thread.ThreadMember
import com.discord.models.message.Message
import com.discord.utilities.search.network.SearchQuery

/**
 * Everything the results screen renders from.
 *
 * [hits] accumulates across pages — a "load more" response is appended to what is already shown —
 * while [totalResults] stays the count the server reported for the *first* page, which is what the
 * result-count header displays.
 */
data class SearchState(
    val queryFetchState: QueryFetchState,
    val searchQuery: SearchQuery? = null,
    val threads: List<Channel>? = null,
    val threadMembers: List<ThreadMember>? = null,
    val hits: List<Message>? = null,
    val hasMore: Boolean = false,
    val totalResults: Int = 0,
)
