package com.discord.utilities.search.network

import com.discord.models.domain.ModelSearchResponse
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.search.SearchTarget
import rx.Observable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Issues one `messages/search` request and keeps re-issuing it while the server says it is still
 * indexing.
 *
 * A guild that has never been searched answers `111000` plus a `retry_after`; the client is expected
 * to wait exactly that long and ask again, incrementing an attempt counter the server uses to decide
 * how hard to work. The retry loop lives here rather than in the store so the store only ever sees
 * a settled answer — except for the indexing responses themselves, which are emitted so the UI can
 * show the "still indexing" row while it waits.
 */
open class SearchFetcher {

    private val indexingRetryCount = AtomicInteger(0)

    open fun makeQuery(
        searchTarget: SearchTarget,
        oldestMessageId: Long?,
        searchQuery: SearchQuery,
    ): Observable<ModelSearchResponse> {
        val resubscribeDelayMillis = AtomicLong()
        return restObservable(searchTarget, oldestMessageId, searchQuery)
            .doOnNext { response ->
                if (response.isIndexing) {
                    resubscribeDelayMillis.set(response.retryMillis)
                    indexingRetryCount.incrementAndGet()
                }
            }
            .repeatWhen { completions ->
                completions.flatMap { Observable.timer(resubscribeDelayMillis.get(), TimeUnit.MILLISECONDS) }
            }
            .takeUntil { !it.isIndexing }
    }

    private fun restObservable(
        searchTarget: SearchTarget,
        oldestMessageId: Long?,
        searchQuery: SearchQuery,
    ): Observable<ModelSearchResponse> {
        val params = searchQuery.params
        return Observable
            .defer {
                // Read inside `defer` so each retry sends the attempt count as of that attempt.
                val attempts = indexingRetryCount.get().takeIf { it != 0 }
                when (searchTarget.type) {
                    SearchTarget.Type.GUILD -> RestAPI.api.searchGuildMessages(
                        guildId = searchTarget.id,
                        oldestMessageId = oldestMessageId,
                        authorIds = params[SearchQuery.PARAM_AUTHOR_ID],
                        mentionsIds = params[SearchQuery.PARAM_MENTIONS],
                        inChannelIds = params[SearchQuery.PARAM_CHANNEL_ID],
                        has = params[SearchQuery.PARAM_HAS],
                        content = params[SearchQuery.PARAM_CONTENT],
                        attempts = attempts,
                        includeNsfw = searchQuery.includeNsfw,
                    )
                    SearchTarget.Type.CHANNEL -> RestAPI.api.searchChannelMessages(
                        channelId = searchTarget.id,
                        oldestMessageId = oldestMessageId,
                        authorIds = params[SearchQuery.PARAM_AUTHOR_ID],
                        mentionsIds = params[SearchQuery.PARAM_MENTIONS],
                        has = params[SearchQuery.PARAM_HAS],
                        content = params[SearchQuery.PARAM_CONTENT],
                        attempts = attempts,
                        includeNsfw = searchQuery.includeNsfw,
                    )
                }
            }
            .restSubscribeOn()
    }

    private val ModelSearchResponse.isIndexing: Boolean
        get() = errorCode == ERROR_CODE_INDEXING

    companion object {
        /** `INDEX_NOT_READY`: the guild's message index is still being built. */
        const val ERROR_CODE_INDEXING = 111000
    }
}
