package com.discord.models.domain

import com.discord.api.channel.Channel
import com.discord.api.message.Message
import com.discord.api.search.SearchCursor
import com.discord.api.thread.ThreadMember

/**
 * Result of `POST {guilds|channels|users/@me}/messages/search/tabs`.
 *
 * The tabs endpoints run several queries in one round trip and key the results by tab name. Only
 * `/users/@me` is exclusive to this family — it is the sole way to search across every DM at once,
 * which is why this exists alongside [ModelSearchResponse] rather than replacing it.
 *
 * Indexing state lives on the envelope, not per tab.
 */
data class ModelSearchTabsResponse(
    val tabs: Map<String, Tab>? = null,
    val analyticsId: String? = null,
    val message: String? = null,
    val errorCode: Int? = null,
    val documentsIndexed: Int? = null,
    val doingDeepHistoricalIndex: Boolean = false,
    val retryAfter: Int? = null,
) {
    data class Tab(
        val messages: List<List<Message>>? = null,
        val totalResults: Int = 0,
        val cursor: SearchCursor? = null,
        val channels: List<Channel>? = null,
        val threads: List<Channel>? = null,
        val members: List<ThreadMember>? = null,
    )

    /** Flattens one tab into the shape the rest of the search pipeline already consumes. */
    fun toSearchResponse(tab: String): ModelSearchResponse {
        val result = tabs?.get(tab)
        return ModelSearchResponse(
            totalResults = result?.totalResults ?: 0,
            messages = result?.messages,
            threads = result?.threads,
            members = result?.members,
            analyticsId = analyticsId,
            message = message,
            errorCode = errorCode,
            documentIndexed = documentsIndexed,
            retryAfter = retryAfter,
            cursor = result?.cursor?.takeUnless(SearchCursor::isEmpty),
        )
    }

    companion object {
        const val TAB_MESSAGES = "messages"
    }
}
