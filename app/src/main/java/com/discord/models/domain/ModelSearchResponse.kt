package com.discord.models.domain

import com.discord.api.channel.Channel
import com.discord.api.message.Message
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
) {
    val retryMillis: Long = (retryAfter ?: 0) * 1000L

    val hits: List<Message> by lazy {
        messages?.mapNotNull { group -> group.firstOrNull { it.hit == true } } ?: emptyList()
    }
}
