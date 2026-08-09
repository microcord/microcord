package com.discord.utilities.search.network

import com.discord.models.domain.ModelSearchResponse
import com.discord.models.domain.ModelSearchTabsResponse
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import com.discord.api.search.SearchCursor
import com.discord.utilities.search.SearchSortMode
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
        sortMode: SearchSortMode = SearchSortMode.DEFAULT,
        cursor: SearchCursor? = null,
    ): Observable<ModelSearchResponse> {
        val resubscribeDelayMillis = AtomicLong()
        return restObservable(searchTarget, oldestMessageId, searchQuery, sortMode, cursor)
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
        sortMode: SearchSortMode,
        cursor: SearchCursor?,
    ): Observable<ModelSearchResponse> {
        val params = searchQuery.params
        return Observable
            .defer {
                // Read inside `defer` so each retry sends the attempt count as of that attempt.
                val attempts = indexingRetryCount.get().takeIf { it != 0 }
                when (searchTarget.type) {
                    SearchTarget.Type.GUILD -> RestAPI.api.searchGuildMessages(
                        guildId = searchTarget.id,
                        oldestMessageId = oldestMessageId ?: params.snowflake(SearchQuery.PARAM_MAX_ID),
                        authorIds = params[SearchQuery.PARAM_AUTHOR_ID],
                        mentionsIds = params[SearchQuery.PARAM_MENTIONS],
                        inChannelIds = params[SearchQuery.PARAM_CHANNEL_ID],
                        has = params[SearchQuery.PARAM_HAS],
                        content = params[SearchQuery.PARAM_CONTENT],
                        attempts = attempts,
                        includeNsfw = searchQuery.includeNsfw,
                        newestMessageId = params.snowflake(SearchQuery.PARAM_MIN_ID),
                        authorTypes = params[SearchQuery.PARAM_AUTHOR_TYPE],
                        pinned = params.boolean(SearchQuery.PARAM_PINNED),
                        linkHostnames = params[SearchQuery.PARAM_LINK_HOSTNAME],
                        attachmentFilenames = params[SearchQuery.PARAM_ATTACHMENT_FILENAME],
                        attachmentExtensions = params[SearchQuery.PARAM_ATTACHMENT_EXTENSION],
                        sortBy = sortMode.sortBy,
                        sortOrder = sortMode.sortOrder,
                    )
                    SearchTarget.Type.CHANNEL -> RestAPI.api.searchChannelMessages(
                        channelId = searchTarget.id,
                        oldestMessageId = oldestMessageId ?: params.snowflake(SearchQuery.PARAM_MAX_ID),
                        authorIds = params[SearchQuery.PARAM_AUTHOR_ID],
                        mentionsIds = params[SearchQuery.PARAM_MENTIONS],
                        has = params[SearchQuery.PARAM_HAS],
                        content = params[SearchQuery.PARAM_CONTENT],
                        attempts = attempts,
                        includeNsfw = searchQuery.includeNsfw,
                        newestMessageId = params.snowflake(SearchQuery.PARAM_MIN_ID),
                        authorTypes = params[SearchQuery.PARAM_AUTHOR_TYPE],
                        pinned = params.boolean(SearchQuery.PARAM_PINNED),
                        linkHostnames = params[SearchQuery.PARAM_LINK_HOSTNAME],
                        attachmentFilenames = params[SearchQuery.PARAM_ATTACHMENT_FILENAME],
                        attachmentExtensions = params[SearchQuery.PARAM_ATTACHMENT_EXTENSION],
                        sortBy = sortMode.sortBy,
                        sortOrder = sortMode.sortOrder,
                    )
                    // Cross-DM search has no GET form; the tabs endpoint is the only way in, so the
                    // single `messages` tab is unwrapped back into the shape everything else uses.
                    SearchTarget.Type.DMS -> RestAPI.api
                        .searchDmMessages(dmSearchBody(params, searchQuery.includeNsfw, sortMode, cursor))
                        .map { it.toSearchResponse(ModelSearchTabsResponse.TAB_MESSAGES) }
                }
            }
            .restSubscribeOn()
    }

    private fun dmSearchBody(
        params: Map<String, List<String>>,
        includeNsfw: Boolean,
        sortMode: SearchSortMode,
        cursor: SearchCursor?,
    ) = RestAPIParams.SearchTabs(
        tabs = mapOf(
            ModelSearchTabsResponse.TAB_MESSAGES to RestAPIParams.SearchTabs.Tab(
                content = params[SearchQuery.PARAM_CONTENT]?.firstOrNull(),
                authorId = params[SearchQuery.PARAM_AUTHOR_ID],
                mentions = params[SearchQuery.PARAM_MENTIONS],
                has = params[SearchQuery.PARAM_HAS],
                authorType = params[SearchQuery.PARAM_AUTHOR_TYPE],
                linkHostname = params[SearchQuery.PARAM_LINK_HOSTNAME],
                attachmentExtension = params[SearchQuery.PARAM_ATTACHMENT_EXTENSION],
                attachmentFilename = params[SearchQuery.PARAM_ATTACHMENT_FILENAME],
                pinned = params.boolean(SearchQuery.PARAM_PINNED),
                minId = params[SearchQuery.PARAM_MIN_ID]?.firstOrNull(),
                maxId = params[SearchQuery.PARAM_MAX_ID]?.firstOrNull(),
                sortBy = sortMode.sortBy,
                sortOrder = sortMode.sortOrder,
                // Echoed back verbatim from the previous page; absent on the first.
                cursor = cursor,
            ),
        ),
        includeNsfw = includeNsfw,
        // `in:` names private channels here rather than guild channels, and the tabs endpoints take
        // it on the envelope instead of inside the tab.
        channelIds = params[SearchQuery.PARAM_CHANNEL_ID],
    )

    private fun Map<String, List<String>>.snowflake(key: String): Long? =
        this[key]?.firstOrNull()?.toLongOrNull()

    private fun Map<String, List<String>>.boolean(key: String): Boolean? =
        this[key]?.firstOrNull()?.toBooleanStrictOrNull()

    private val ModelSearchResponse.isIndexing: Boolean
        get() = errorCode == ERROR_CODE_INDEXING

    companion object {
        /** `INDEX_NOT_READY`: the guild's message index is still being built. */
        const val ERROR_CODE_INDEXING = 111000

    }
}
