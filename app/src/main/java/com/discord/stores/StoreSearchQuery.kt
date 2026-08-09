package com.discord.stores

import com.discord.models.domain.ModelSearchResponse
import com.discord.models.message.Message
import com.discord.stores.StoreSearch.SearchTarget
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationBuffered
import com.discord.api.search.SearchCursor
import com.discord.utilities.search.SearchSortMode
import com.discord.utilities.search.network.SearchFetcher
import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.network.state.QueryFetchState
import com.discord.utilities.search.network.state.SearchState
import com.discord.utilities.search.query.node.QueryNode
import com.discord.utilities.search.query.node.QueryNode.Preprocessor.preprocess
import com.discord.utilities.search.query.parsing.QueryParser
import com.discord.utilities.search.strings.SearchStringProvider
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject

class StoreSearchQuery(
    private val searchFetcher: SearchFetcher = SearchFetcher(),
) {
    private val searchStateSubject = SerializedSubject(BehaviorSubject.create(SEARCH_STATE_NONE))
    private var currentSearchState = SEARCH_STATE_NONE
    private var querySubscription: Subscription? = null
    private var sortMode = SearchSortMode.DEFAULT
    private var lastTarget: SearchTarget? = null

    val state: Observable<SearchState> = searchStateSubject.distinctUntilChanged()

    fun parseAndQuery(
        searchStore: StoreSearch,
        searchTarget: SearchTarget,
        queryString: String,
        searchStringProvider: SearchStringProvider,
        includeNsfw: Boolean,
    ) = parseAndQuery(
        searchData = searchStore.storeSearchData,
        searchTarget = searchTarget,
        queryString = queryString,
        searchStringProvider = searchStringProvider,
        includeNsfw = includeNsfw,
        onQueryParsed = { searchStore.persistQuery(searchTarget, it) },
    )

    internal fun parseAndQuery(
        searchData: StoreSearchData,
        searchTarget: SearchTarget,
        queryString: String,
        searchStringProvider: SearchStringProvider,
        includeNsfw: Boolean,
        onQueryParsed: (List<QueryNode>) -> Unit,
    ) {
        unsubscribe()
        if (queryString.isBlank()) {
            updateAndPublish(SEARCH_STATE_NONE)
            return
        }
        updateAndPublish(SearchState(QueryFetchState.IN_PROGRESS))
        Observable.combineLatest(
            Observable.just(queryString),
            searchData.get(),
        ) { text, searchData ->
            val nodes = QueryParser(searchStringProvider).parse(text, Unit).toMutableList()
            nodes.preprocess(searchData)
            onQueryParsed(nodes)
            SearchQuery.Builder().setIncludeNsfw(includeNsfw).buildFrom(nodes, searchData)
        }
            .map<SearchQuery?> { it }
            .timeout(PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS, Observable.just<SearchQuery?>(null))
            .take(1)
            .computationBuffered()
            .appSubscribe(errorTag = "parseAndQuery") { query ->
                if (query == null) handleError() else performInitialLoad(searchTarget, query)
            }
    }

    @Synchronized
    fun loadMore(searchTarget: SearchTarget, oldestMessageId: Long) {
        val query = currentSearchState.searchQuery ?: return
        if (currentSearchState.queryFetchState != QueryFetchState.COMPLETED || !currentSearchState.hasMore) return
        unsubscribe()
        val cursor = currentSearchState.cursor
        updateAndPublish(currentSearchState.copy(queryFetchState = QueryFetchState.LOADING_MORE, hasMore = false))
        makeQuery(query, searchTarget, oldestMessageId, isInitialLoad = false, cursor = cursor)
    }

    /**
     * Re-runs the current query under a new ordering.
     *
     * The whole result set is discarded rather than re-sorted: a cursor is only valid for the sort
     * that minted it, and the server holds far more results than the client has fetched.
     */
    @Synchronized
    fun setSortMode(mode: SearchSortMode) {
        if (mode == sortMode) return
        sortMode = mode
        val query = currentSearchState.searchQuery ?: return
        val target = lastTarget ?: return
        unsubscribe()
        updateAndPublish(SearchState(QueryFetchState.IN_PROGRESS, query))
        makeQuery(query, target, oldestMessageId = null, isInitialLoad = true)
    }

    fun getSortMode(): SearchSortMode = sortMode

    fun clear() {
        unsubscribe()
        updateAndPublish(SEARCH_STATE_NONE)
    }

    private fun performInitialLoad(searchTarget: SearchTarget, query: SearchQuery) {
        unsubscribe()
        lastTarget = searchTarget
        makeQuery(query, searchTarget, oldestMessageId = null, isInitialLoad = true)
    }

    private fun makeQuery(
        query: SearchQuery,
        searchTarget: SearchTarget,
        oldestMessageId: Long?,
        isInitialLoad: Boolean,
        cursor: SearchCursor? = null,
    ) {
        searchFetcher.makeQuery(searchTarget.toUtility(), oldestMessageId, query, sortMode, cursor).appSubscribe(
            errorTag = "makeQuery",
            subscriptionHandler = { querySubscription = it },
            errorHandler = { handleError() },
            onNext = { handleResponse(query, it, isInitialLoad) },
        )
    }

    private fun isFromHiddenAuthor(message: Message): Boolean =
        message.author?.id?.let(StoreStream.getUserRelationships()::isBlockedOrIgnored) == true

    @Synchronized
    private fun handleResponse(query: SearchQuery, response: ModelSearchResponse, isInitialLoad: Boolean) {
        val next = when {
            response.errorCode == null -> {
                val total = if (isInitialLoad) response.totalResults else currentSearchState.totalResults
                val hits = if (isInitialLoad) ArrayList<Message>() else ArrayList(currentSearchState.hits.orEmpty())
                // Mobile drops results from blocked and ignored authors in the store; the
                // reveal affordance that desktop offers instead has no mobile component.
                hits += response.hits.map(::Message).filterNot(::isFromHiddenAuthor)
                SearchState(
                    queryFetchState = QueryFetchState.COMPLETED,
                    searchQuery = query,
                    threads = response.threads,
                    threadMembers = response.members,
                    hits = hits,
                    // A cursor endpoint is authoritative about whether more exist; the count-based
                    // test is only meaningful for the offset endpoints, which return no cursor.
                    hasMore = if (response.cursor != null) true else total > hits.size,
                    totalResults = total,
                    cursor = response.cursor,
                )
            }
            response.errorCode == SearchFetcher.ERROR_CODE_INDEXING ->
                SearchState(QueryFetchState.INDEXING, query)
            else -> SearchState(QueryFetchState.FAILED, query)
        }
        updateAndPublish(next)
    }

    private fun handleError() = updateAndPublish(SearchState(QueryFetchState.FAILED))

    @Synchronized
    private fun updateAndPublish(state: SearchState) {
        currentSearchState = state
        searchStateSubject.onNext(state)
    }

    @Synchronized
    private fun unsubscribe() {
        querySubscription?.unsubscribe()
        querySubscription = null
    }

    private fun SearchTarget.toUtility() = com.discord.utilities.search.SearchTarget(
        com.discord.utilities.search.SearchTarget.Type.valueOf(type.name),
        id,
    )

    private companion object {
        val SEARCH_STATE_NONE = SearchState(QueryFetchState.NONE)
        const val PARSE_TIMEOUT_SECONDS = 1L
    }
}
