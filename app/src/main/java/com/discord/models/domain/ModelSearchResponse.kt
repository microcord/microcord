package com.discord.models.domain

import com.discord.api.channel.Channel
import com.discord.api.message.Message
import com.discord.api.search.SearchCursor
import com.discord.api.thread.ThreadMember

/**
 * Result of `channels|guilds/{id}/messages/search`.
 *
 * [messages] arrives as one group per result: the matched message plus the surrounding context
 * rows. [hits] narrows each group down to the message the server flagged with `hit`.
 *
 * When indexing is still running the server answers with [errorCode] / [retryAfter] instead of
 * results, which is why every result field is nullable.
 */
data class ModelSearchResponse(
    val totalResults: Int = 0,
    val messages: List<List<Message>>? = null,
    val threads: List<Channel>? = null,
    val members: List<ThreadMember>? = null,
    val analyticsId: String? = null,
    val message: String? = null,
    val errorCode: Int? = null,
    val documentIndexed: Int? = null,
    val retryAfter: Int? = null,
    /** Only the tabs endpoints paginate by cursor; null on the GET endpoints. */
    val cursor: SearchCursor? = null,
) {
    /**
     * DELIBERATE DIVERGENCE from the reference — computed, where the reference stores.
     *
     * Every constructor parameter here is defaulted, so Kotlin emits a no-arg constructor that
     * Gson uses; the body initialiser therefore runs against a still-null [retryAfter] *before*
     * the parsed fields are injected, and the reference's value is permanently `0`. (`hits`
     * escapes this only because it is `by lazy`.)
     *
     * That means the reference re-issues an indexing search after 0 ms. Reproducing it would put
     * this client in a zero-delay retry loop against the live backend, which the project's
     * live-backend safety rules forbid — so this is computed on read and honours the
     * server-supplied delay.
     */
    val retryMillis: Long get() = (retryAfter ?: 0) * 1000L

    val hits: List<Message> by lazy {
        messages?.mapNotNull { group -> group.firstOrNull { it.hit == true } } ?: emptyList()
    }
}
