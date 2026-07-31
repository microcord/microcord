package com.discord.stores

import com.discord.api.user.NsfwAllowance
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.search.history.PersistedSearchHistoryCache
import com.discord.utilities.search.history.SearchHistoryCache
import com.discord.utilities.search.network.SearchFetcher
import com.discord.utilities.search.network.state.QueryFetchState
import com.discord.utilities.search.network.state.SearchState
import com.discord.utilities.search.query.node.QueryNode
import com.discord.utilities.search.strings.SearchStringProvider
import com.discord.utilities.search.suggestion.SearchSuggestionEngine
import com.discord.widgets.search.SearchQueryStore
import com.discord.widgets.search.SearchStore
import rx.Observable
import rx.Subscription
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject

class StoreSearch(
    val storeSearchData: StoreSearchData,
    val storeSearchInput: StoreSearchInput,
    val storeSearchQuery: StoreSearchQuery,
    private val stream: StoreStream,
    private val dispatcher: Dispatcher,
    private val storeGuildsNsfw: StoreGuildsNsfw,
    private val storeUser: StoreUser,
    private val historyCache: SearchHistoryCache,
) {
    private val displayStateSubject = SerializedSubject(BehaviorSubject.create(DisplayState.SUGGESTIONS))
    private val searchTargetSubject = SerializedSubject(BehaviorSubject.create(null as SearchTarget?))
    private var searchTarget: SearchTarget? = null
    private var subscription: Subscription? = null

    private val widgetStore = object : SearchStore {
        override val storeSearchInput = this@StoreSearch.storeSearchInput
        override val storeSearchData = this@StoreSearch.storeSearchData
        override val storeSearchQuery = object : SearchQueryStore {
            override val state: Observable<SearchState> = this@StoreSearch.storeSearchQuery.state

            override fun parseAndQuery(
                searchTarget: com.discord.utilities.search.SearchTarget,
                queryString: String,
                searchStringProvider: SearchStringProvider,
                includeNsfw: Boolean,
                onQueryParsed: (List<QueryNode>) -> Unit,
            ) = this@StoreSearch.storeSearchQuery.parseAndQuery(
                this@StoreSearch.storeSearchData,
                searchTarget.toStoreTarget(),
                queryString,
                searchStringProvider,
                includeNsfw,
                onQueryParsed,
            )

            override fun loadMore(searchTarget: com.discord.utilities.search.SearchTarget, oldestMessageId: Long) =
                this@StoreSearch.storeSearchQuery.loadMore(searchTarget.toStoreTarget(), oldestMessageId)

            override fun clear() = this@StoreSearch.storeSearchQuery.clear()
        }

        override fun initForGuild(guildId: Long, searchStringProvider: SearchStringProvider) =
            this@StoreSearch.initForGuild(guildId, searchStringProvider)

        override fun initForChannel(channelId: Long, searchStringProvider: SearchStringProvider) =
            this@StoreSearch.initForChannel(channelId, searchStringProvider)

        override fun getDisplayState(): Observable<SearchStore.DisplayState> =
            this@StoreSearch.getDisplayState().map { SearchStore.DisplayState.valueOf(it.name) }

        override fun getHistory() = this@StoreSearch.getHistory()
        override fun loadInitial(queryString: String, searchStringProvider: SearchStringProvider) =
            this@StoreSearch.loadInitial(queryString, searchStringProvider)
        override fun loadMore(oldestMessageId: Long) = this@StoreSearch.loadMore(oldestMessageId)
        override fun clearHistory() = this@StoreSearch.clearHistory()
        override fun clear() = this@StoreSearch.clear()
    }

    constructor(
        stream: StoreStream,
        dispatcher: Dispatcher,
        storeGuildsNsfw: StoreGuildsNsfw,
        storeUser: StoreUser,
        observationDeck: ObservationDeck,
        storeChannels: StoreChannels,
        storeGuilds: StoreGuilds,
    ) : this(
        StoreSearchData(observationDeck, storeChannels, storeUser, storeGuilds),
        StoreSearchInput(),
        StoreSearchQuery(SearchFetcher()),
        stream,
        dispatcher,
        storeGuildsNsfw,
        storeUser,
        PersistedSearchHistoryCache(),
    )

    fun initForGuild(guildId: Long, searchStringProvider: SearchStringProvider) {
        init(SearchTarget(SearchTarget.Type.GUILD, guildId), searchStringProvider)
        SearchSuggestionEngine.targetGuildId = guildId
    }

    fun initForChannel(channelId: Long, searchStringProvider: SearchStringProvider) {
        init(SearchTarget(SearchTarget.Type.CHANNEL, channelId), searchStringProvider)
        SearchSuggestionEngine.targetGuildId = null
    }

    @Synchronized
    private fun init(searchTarget: SearchTarget, searchStringProvider: SearchStringProvider) {
        if (this.searchTarget == searchTarget) return
        updateTarget(searchTarget)
        storeSearchData.init(searchTarget)
        storeSearchInput.init(searchStringProvider)
        Observable.merge(
            storeSearchQuery.state
                .filter { it.queryFetchState != QueryFetchState.NONE }
                .map { DisplayState.RESULTS },
            storeSearchInput.currentParsedInput.map { DisplayState.SUGGESTIONS },
        ).appSubscribe(
            errorClass = StoreSearch::class.java,
            subscriptionHandler = ::handleSubscription,
        ) { displayState ->
            if (displayState != DisplayState.RESULTS) storeSearchQuery.clear()
            displayStateSubject.onNext(displayState)
        }
        storeSearchQuery.state.appSubscribe(errorClass = StoreSearch::class.java) { searchState ->
            dispatcher.schedule { stream.handleSearchFinish(searchState) }
        }
    }

    fun getDisplayState(): Observable<DisplayState> =
        displayStateSubject.computationLatest().distinctUntilChanged()

    fun getHistory(): Observable<Collection<List<QueryNode>>> = searchTargetSubject.switchMap { target ->
        if (target == null) Observable.just<Collection<List<QueryNode>>>(emptyList())
        else historyCache.getHistory(target.toUtility())
    }

    fun loadInitial(queryString: String, searchStringProvider: SearchStringProvider) {
        val target = searchTarget ?: return
        storeSearchQuery.parseAndQuery(this, target, queryString, searchStringProvider, target.includeNsfw())
    }

    fun loadMore(oldestMessageId: Long) {
        searchTarget?.let { storeSearchQuery.loadMore(it, oldestMessageId) }
    }

    fun clearHistory() {
        searchTarget?.let { historyCache.clear(it.toUtility()) }
    }

    @Synchronized
    fun clear() {
        updateTarget(null)
        handleSubscription(null)
        displayStateSubject.onNext(DisplayState.SUGGESTIONS)
        storeSearchQuery.clear()
        storeSearchInput.clear()
        storeSearchData.clear()
    }

    internal fun persistQuery(searchTarget: SearchTarget, query: List<QueryNode>) =
        historyCache.persistQuery(searchTarget.toUtility(), query)

    internal fun asWidgetStore(): SearchStore = widgetStore

    private fun SearchTarget.includeNsfw(): Boolean =
        storeUser.getMe().nsfwAllowance != NsfwAllowance.DISALLOWED &&
            (type != SearchTarget.Type.GUILD || storeGuildsNsfw.isGuildNsfwGateAgreed(id))

    @Synchronized
    private fun updateTarget(searchTarget: SearchTarget?) {
        this.searchTarget = searchTarget
        searchTargetSubject.onNext(searchTarget)
    }

    @Synchronized
    private fun handleSubscription(subscription: Subscription?) {
        this.subscription?.unsubscribe()
        this.subscription = subscription
    }

    private fun SearchTarget.toUtility() = com.discord.utilities.search.SearchTarget(
        com.discord.utilities.search.SearchTarget.Type.valueOf(type.name),
        id,
    )

    private fun com.discord.utilities.search.SearchTarget.toStoreTarget() =
        SearchTarget(SearchTarget.Type.valueOf(type.name), id)

    enum class DisplayState { SUGGESTIONS, RESULTS }

    data class SearchTarget(val type: Type, val id: Long) {
        enum class Type { GUILD, CHANNEL }
    }
}
