package com.discord.stores

import com.discord.models.domain.ModelSearchResponse
import com.discord.models.message.Message
import com.discord.stores.StoreSearch.SearchTarget
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationBuffered
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
        updateAndPublish(currentSearchState.copy(queryFetchState = QueryFetchState.LOADING_MORE, hasMore = false))
        makeQuery(query, searchTarget, oldestMessageId, isInitialLoad = false)
    }

    fun clear() {
        unsubscribe()
        updateAndPublish(SEARCH_STATE_NONE)
    }

    private fun performInitialLoad(searchTarget: SearchTarget, query: SearchQuery) {
        unsubscribe()
        makeQuery(query, searchTarget, oldestMessageId = null, isInitialLoad = true)
    }

    private fun makeQuery(
        query: SearchQuery,
        searchTarget: SearchTarget,
        oldestMessageId: Long?,
        isInitialLoad: Boolean,
    ) {
        searchFetcher.makeQuery(searchTarget.toUtility(), oldestMessageId, query).appSubscribe(
            errorTag = "makeQuery",
            subscriptionHandler = { querySubscription = it },
            errorHandler = { handleError() },
            onNext = { handleResponse(query, it, isInitialLoad) },
        )
    }

    @Synchronized
    private fun handleResponse(query: SearchQuery, response: ModelSearchResponse, isInitialLoad: Boolean) {
        val next = when {
            response.errorCode == null -> {
                val total = if (isInitialLoad) response.totalResults else currentSearchState.totalResults
                val hits = if (isInitialLoad) ArrayList<Message>() else ArrayList(currentSearchState.hits.orEmpty())
                hits += response.hits.map(::Message)
                SearchState(
                    queryFetchState = QueryFetchState.COMPLETED,
                    searchQuery = query,
                    threads = response.threads,
                    threadMembers = response.members,
                    hits = hits,
                    hasMore = total > hits.size,
                    totalResults = total,
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
