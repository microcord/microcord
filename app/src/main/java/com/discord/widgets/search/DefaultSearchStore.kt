package com.discord.widgets.search

import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.user.NsfwAllowance
import com.discord.models.domain.ModelSearchResponse
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.search.SearchTarget
import com.discord.utilities.search.history.PersistedSearchHistoryCache
import com.discord.utilities.search.history.SearchHistoryCache
import com.discord.utilities.search.network.SearchFetcher
import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.network.state.QueryFetchState
import com.discord.utilities.search.network.state.SearchState
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.query.node.QueryNode
import com.discord.utilities.search.query.node.QueryNode.Preprocessor.preprocess
import com.discord.utilities.search.query.node.answer.ChannelNode
import com.discord.utilities.search.query.node.answer.HasAnswerOption
import com.discord.utilities.search.query.node.answer.HasNode
import com.discord.utilities.search.query.node.answer.UserNode
import com.discord.utilities.search.query.node.content.ContentNode
import com.discord.utilities.search.query.node.filter.FilterNode
import com.discord.utilities.search.query.parsing.QueryParser
import com.discord.utilities.search.strings.SearchStringProvider
import com.discord.utilities.search.suggestion.SearchSuggestionEngine
import com.discord.utilities.search.suggestion.entries.ChannelSuggestion
import com.discord.utilities.search.suggestion.entries.UserSuggestion
import com.discord.utilities.search.validation.SearchData
import rx.Observable
import rx.Subscription
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import rx.subjects.SerializedSubject
import java.util.concurrent.TimeUnit

/**
 * Working implementation of the [SearchStore] seam.
 *
 * See [SearchStore] for why this is not under `com.discord.stores`. Everything here is a direct
 * transcription of the reference's four search stores; when the real ones land, this file is
 * deleted.
 */
class DefaultSearchStore(
    override val storeSearchData: SearchDataStore = DefaultSearchDataStore(),
    override val storeSearchInput: SearchInputStore = DefaultSearchInputStore(),
    override val storeSearchQuery: SearchQueryStore = DefaultSearchQueryStore(SearchFetcher(), storeSearchData),
    private val historyCache: SearchHistoryCache = PersistedSearchHistoryCache(),
) : SearchStore {

    private val displayStateSubject =
        SerializedSubject(BehaviorSubject.create(SearchStore.DisplayState.SUGGESTIONS))
    // `create(null as T?)` rather than `create<T?>(null)`: a bare null makes Kotlin pick the
    // inherited static `Observable.create(OnSubscribe)` overload, yielding an Observable with no
    // `onNext`. Same trap as StoreMessagesLoader/StoreRtcConnection/StoreNotices.
    private val searchTargetSubject =
        SerializedSubject(BehaviorSubject.create(null as SearchTarget?))

    private var searchTarget: SearchTarget? = null
    private var subscription: Subscription? = null

    override fun initForGuild(guildId: Long, searchStringProvider: SearchStringProvider) {
        init(SearchTarget(SearchTarget.Type.GUILD, guildId), searchStringProvider)
        SearchSuggestionEngine.targetGuildId = guildId
    }

    override fun initForChannel(channelId: Long, searchStringProvider: SearchStringProvider) {
        init(SearchTarget(SearchTarget.Type.CHANNEL, channelId), searchStringProvider)
        SearchSuggestionEngine.targetGuildId = null
    }

    /**
     * Which pane is in front follows the two inputs directly: any change to the typed query drops
     * back to suggestions, any request that has left `NONE` switches to results. Dropping back also
     * discards the previous results, so a stale hit list can never sit behind a new query.
     */
    @Synchronized
    private fun init(searchTarget: SearchTarget, searchStringProvider: SearchStringProvider) {
        if (this.searchTarget == searchTarget) return
        updateTarget(searchTarget)
        storeSearchData.init(searchTarget)
        storeSearchInput.init(searchStringProvider)

        Observable
            .merge(
                storeSearchQuery.state
                    .filter { it.queryFetchState != QueryFetchState.NONE }
                    .map { SearchStore.DisplayState.RESULTS },
                storeSearchInput.currentParsedInput.map { SearchStore.DisplayState.SUGGESTIONS },
            )
            .appSubscribe(
                errorClass = DefaultSearchStore::class.java,
                subscriptionHandler = ::handleSubscription,
            ) { displayState ->
                if (displayState != SearchStore.DisplayState.RESULTS) storeSearchQuery.clear()
                displayStateSubject.onNext(displayState)
            }
    }

    override fun getDisplayState(): Observable<SearchStore.DisplayState> =
        displayStateSubject.computationLatest().distinctUntilChanged()

    override fun getHistory(): Observable<Collection<List<QueryNode>>> =
        searchTargetSubject.switchMap { target ->
            if (target != null) {
                historyCache.getHistory(target)
            } else {
                Observable.just<Collection<List<QueryNode>>>(emptyList())
            }
        }

    override fun loadInitial(queryString: String, searchStringProvider: SearchStringProvider) {
        val target = searchTarget ?: return
        storeSearchQuery.parseAndQuery(
            searchTarget = target,
            queryString = queryString,
            searchStringProvider = searchStringProvider,
            includeNsfw = target.includeNsfw(),
        ) { query -> historyCache.persistQuery(target, query) }
    }

    override fun loadMore(oldestMessageId: Long) {
        val target = searchTarget ?: return
        storeSearchQuery.loadMore(target, oldestMessageId)
    }

    override fun clearHistory() {
        searchTarget?.let(historyCache::clear)
    }

    @Synchronized
    override fun clear() {
        updateTarget(null)
        handleSubscription(null)
        displayStateSubject.onNext(SearchStore.DisplayState.SUGGESTIONS)
        storeSearchQuery.clear()
        storeSearchInput.clear()
        storeSearchData.clear()
    }

    /**
     * A user who has not confirmed they are an adult never sees age-restricted results. Beyond
     * that the reference also requires the guild's own NSFW gate to have been agreed; `StoreGuildsNsfw`
     * is not reachable from `StoreStream` in this project, so a guild search currently behaves as
     * though the gate were agreed. Private-channel search has no gate either way.
     */
    private fun SearchTarget.includeNsfw(): Boolean =
        StoreStream.getUsers().getMe().nsfwAllowance != NsfwAllowance.DISALLOWED

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
}

/**
 * Parses the search box and applies the edits the suggestion rows make to it.
 *
 * Typing is throttled on the leading edge so the first keystroke parses immediately and the burst
 * that follows collapses; parsing then runs off the main thread because the grammar recompiles a
 * handful of regexes.
 */
class DefaultSearchInputStore : SearchInputStore {

    private val inputSubject = SerializedSubject(BehaviorSubject.create(""))
    private val astSubject = SerializedSubject(BehaviorSubject.create(emptyList<QueryNode>()))
    private val forcedInputSubject = PublishSubject.create<List<QueryNode>>()

    private var inputSubscription: Subscription? = null

    override val currentParsedInput: Observable<List<QueryNode>> get() = astSubject
    override val forcedInput: Observable<List<QueryNode>> get() = forcedInputSubject

    /**
     * A query made only of filter keywords cannot be run — `from:` on its own matches nothing — so
     * the submit affordance waits for at least one node that is not a bare filter.
     */
    override val isInputValid: Observable<Boolean> =
        astSubject
            .leadingEdgeThrottle(VALIDATION_THROTTLE_MS, TimeUnit.MILLISECONDS)
            .map { nodes -> nodes.any { it !is FilterNode } }
            .distinctUntilChanged()

    override fun init(searchStringProvider: SearchStringProvider) {
        val queryParser = QueryParser(searchStringProvider)
        inputSubject
            .leadingEdgeThrottle(PARSE_THROTTLE_MS, TimeUnit.MILLISECONDS)
            .distinctUntilChanged()
            .map { queryParser.parse(it, Unit) }
            .appSubscribe(
                errorClass = DefaultSearchInputStore::class.java,
                subscriptionHandler = ::handleInputSubscription,
                onNext = astSubject::onNext,
            )
    }

    override fun updateInput(input: String) {
        inputSubject.onNext(input)
    }

    override fun clear() {
        handleInputSubscription(null)
        inputSubject.onNext("")
    }

    override fun onFilterClicked(
        filterType: FilterType,
        searchStringProvider: SearchStringProvider,
        query: MutableList<QueryNode>,
    ) {
        val filterNode = FilterNode(
            filterType,
            when (filterType) {
                FilterType.FROM -> searchStringProvider.fromFilterString
                FilterType.MENTIONS -> searchStringProvider.mentionsFilterString
                FilterType.HAS -> searchStringProvider.hasFilterString
                FilterType.IN -> searchStringProvider.inFilterString
            },
        )
        // A filter tapped while the user is mid-word replaces that word; otherwise it is appended.
        val replacementIndex = when {
            query.isEmpty() -> 0
            query.last() is ContentNode -> query.lastIndex
            else -> query.size
        }
        replaceAndPublish(replacementIndex, listOf(filterNode), query)
    }

    override fun onFromUserClicked(
        userSuggestion: UserSuggestion,
        fromFilterString: CharSequence,
        query: MutableList<QueryNode>,
    ) = replaceAndPublish(
        getAnswerReplacementStart(query),
        listOf(
            FilterNode(FilterType.FROM, fromFilterString),
            UserNode(userSuggestion.userName, userSuggestion.discriminator),
        ),
        query,
    )

    override fun onMentionsUserClicked(
        userSuggestion: UserSuggestion,
        mentionsFilterString: CharSequence,
        query: MutableList<QueryNode>,
    ) = replaceAndPublish(
        getAnswerReplacementStart(query),
        listOf(
            FilterNode(FilterType.MENTIONS, mentionsFilterString),
            UserNode(userSuggestion.userName, userSuggestion.discriminator),
        ),
        query,
    )

    override fun onInChannelClicked(
        channelSuggestion: ChannelSuggestion,
        inFilterString: CharSequence,
        query: MutableList<QueryNode>,
    ) = replaceAndPublish(
        getAnswerReplacementStart(query),
        listOf(
            FilterNode(FilterType.IN, inFilterString),
            ChannelNode(channelSuggestion.channelName),
        ),
        query,
    )

    override fun onHasClicked(
        hasAnswerOption: HasAnswerOption,
        hasFilterString: CharSequence,
        hasAnswerString: CharSequence,
        query: MutableList<QueryNode>,
    ) = replaceAndPublish(
        getAnswerReplacementStart(query),
        listOf(
            FilterNode(FilterType.HAS, hasFilterString),
            HasNode(hasAnswerOption, hasAnswerString),
        ),
        query,
    )

    override fun onQueryClicked(query: List<QueryNode>) {
        forcedInputSubject.onNext(query)
    }

    /**
     * Where a completed `filter + answer` pair should be written.
     *
     * Tapping a user while `from:` is the last token replaces that token; tapping one while a
     * partial answer has been typed replaces both. Anything else is not a completable position and
     * yields -1, which [replaceAndPublish] ignores.
     */
    private fun getAnswerReplacementStart(query: List<QueryNode>): Int {
        if (query.size <= 1) return 0
        val lastIndex = query.lastIndex
        val last = query[lastIndex]
        val previous = query[lastIndex - 1]
        return when {
            last is FilterNode -> lastIndex
            last is ContentNode && previous is FilterNode -> lastIndex - 1
            else -> -1
        }
    }

    private fun replaceAndPublish(
        replacementIndex: Int,
        replacement: List<QueryNode>,
        query: MutableList<QueryNode>,
    ) {
        if (replacementIndex < 0 || replacementIndex > query.size) return
        query.subList(replacementIndex, query.size).clear()
        query.addAll(replacementIndex, replacement)
        forcedInputSubject.onNext(query)
    }

    @Synchronized
    private fun handleInputSubscription(subscription: Subscription?) {
        inputSubscription?.unsubscribe()
        inputSubscription = subscription
    }

    private companion object {
        const val PARSE_THROTTLE_MS = 100L
        const val VALIDATION_THROTTLE_MS = 50L
    }
}

/** Owns the in-flight request and the pages accumulated so far. */
class DefaultSearchQueryStore(
    private val searchFetcher: SearchFetcher,
    private val searchDataStore: SearchDataStore,
) : SearchQueryStore {

    private val searchStateSubject = SerializedSubject(BehaviorSubject.create(SEARCH_STATE_NONE))
    private var currentSearchState: SearchState = SEARCH_STATE_NONE
    private var querySubscription: Subscription? = null

    override val state: Observable<SearchState>
        get() = searchStateSubject.distinctUntilChanged()

    override fun parseAndQuery(
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

        // The query is parsed against whatever the search data says *now*. Search data for a large
        // guild is throttled, so the wait is bounded: if it has not arrived within a second the
        // search fails rather than hanging on a spinner.
        val parsed: Observable<SearchQuery?> = Observable
            .combineLatest(
                Observable.just(queryString),
                searchDataStore.get(),
            ) { text, searchData ->
                val nodes = QueryParser(searchStringProvider).parse(text, Unit).toMutableList()
                nodes.preprocess(searchData)
                onQueryParsed(nodes)
                SearchQuery.Builder().setIncludeNsfw(includeNsfw).buildFrom(nodes, searchData)
            }
            .take(1)
            .map<SearchQuery?> { it }

        parsed
            .timeout(PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS, Observable.just<SearchQuery?>(null))
            .appSubscribe(errorTag = "parseAndQuery") { searchQuery ->
                if (searchQuery == null) handleError() else performInitialLoad(searchTarget, searchQuery)
            }
    }

    @Synchronized
    override fun loadMore(searchTarget: SearchTarget, oldestMessageId: Long) {
        val searchQuery = currentSearchState.searchQuery ?: return
        if (currentSearchState.queryFetchState != QueryFetchState.COMPLETED) return
        if (!currentSearchState.hasMore) return

        unsubscribe()
        updateAndPublish(
            currentSearchState.copy(
                queryFetchState = QueryFetchState.LOADING_MORE,
                hasMore = false,
            ),
        )
        makeQuery(searchQuery, searchTarget, oldestMessageId, isInitialLoad = false)
    }

    override fun clear() {
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
        searchFetcher.makeQuery(searchTarget, oldestMessageId, query).appSubscribe(
            errorTag = "makeQuery",
            subscriptionHandler = { querySubscription = it },
            errorHandler = { _ -> handleError() },
        ) { response -> handleResponse(query, response, isInitialLoad) }
    }

    /**
     * A first page replaces the hit list and adopts the server's total; a later page appends to it
     * and keeps the original total, because the server recomputes it per request.
     */
    @Synchronized
    private fun handleResponse(
        searchQuery: SearchQuery,
        response: ModelSearchResponse,
        isInitialLoad: Boolean,
    ) {
        val errorCode = response.errorCode
        val next = when {
            errorCode == null -> {
                val totalResults =
                    if (isInitialLoad) response.totalResults else currentSearchState.totalResults
                val hits = if (isInitialLoad) {
                    ArrayList<Message>()
                } else {
                    ArrayList(currentSearchState.hits.orEmpty())
                }
                hits += response.hits.map { Message(it) }
                SearchState(
                    queryFetchState = QueryFetchState.COMPLETED,
                    searchQuery = searchQuery,
                    threads = response.threads,
                    threadMembers = response.members,
                    hits = hits,
                    hasMore = totalResults > hits.size,
                    totalResults = totalResults,
                )
            }
            errorCode == SearchFetcher.ERROR_CODE_INDEXING ->
                SearchState(QueryFetchState.INDEXING, searchQuery)
            else -> SearchState(QueryFetchState.FAILED, searchQuery)
        }
        updateAndPublish(next)
    }

    private fun handleError() {
        updateAndPublish(SearchState(QueryFetchState.FAILED))
    }

    @Synchronized
    private fun updateAndPublish(searchState: SearchState) {
        currentSearchState = searchState
        searchStateSubject.onNext(searchState)
    }

    @Synchronized
    private fun unsubscribe() {
        querySubscription?.unsubscribe()
        querySubscription = null
    }

    private companion object {
        val SEARCH_STATE_NONE = SearchState(QueryFetchState.NONE)
        const val PARSE_TIMEOUT_SECONDS = 1L
    }
}

/**
 * Keeps [SearchData] in step with the stores while a target is bound.
 *
 * Guild data is throttled hard — it is derived from the whole member list, every channel and every
 * channel permission — because it only has to be fresh enough to validate the token the user is
 * currently typing.
 */
class DefaultSearchDataStore : SearchDataStore {

    private val searchDataSubject = BehaviorSubject.create(SearchData())
    private var subscription: Subscription? = null

    override fun get(): Observable<SearchData> = searchDataSubject

    override fun init(searchTarget: SearchTarget) {
        val source = when (searchTarget.type) {
            SearchTarget.Type.GUILD -> guildSearchData(searchTarget.id)
            SearchTarget.Type.CHANNEL -> channelSearchData(searchTarget.id)
        }
        source
            .distinctUntilChanged()
            .appSubscribe(
                errorClass = DefaultSearchDataStore::class.java,
                subscriptionHandler = ::handleSubscription,
                onNext = searchDataSubject::onNext,
            )
    }

    override fun clear() {
        handleSubscription(null)
        searchDataSubject.onNext(SearchData())
    }

    private fun guildSearchData(guildId: Long): Observable<SearchData> = Observable
        .combineLatest(
            StoreStream.getGuilds().observeComputed(guildId),
            StoreStream.getUsers().observeAllUsers(),
            StoreStream.getChannels().observeChannelsForGuild(guildId)
                .map { channels -> channels.filterValues { it.isGuildTextyChannel() } },
            StoreStream.getPermissions().observeChannelPermissionsForGuild(guildId),
        ) { members, users, channels, permissions ->
            SearchData.Builder().buildForGuild(members, users, channels, permissions)
        }
        .leadingEdgeThrottle(GUILD_THROTTLE_SECONDS, TimeUnit.SECONDS)

    private fun channelSearchData(channelId: Long): Observable<SearchData> = Observable
        .combineLatest(
            StoreStream.getChannels().observeChannel(channelId),
            StoreStream.getUsers().observeMe(),
            StoreStream.getGuilds().observeComputed(),
        ) { channel, meUser, allMembers ->
            val guildMembers = allMembers[channel?.guildId ?: 0L].orEmpty()
            SearchData.Builder().buildForChannel(channel, meUser, guildMembers)
        }

    @Synchronized
    private fun handleSubscription(subscription: Subscription?) {
        this.subscription?.unsubscribe()
        this.subscription = subscription
    }

    private companion object {
        const val GUILD_THROTTLE_SECONDS = 3L
    }
}

/**
 * Where the search screens get their store.
 *
 * A single instance is shared by [WidgetSearch] and its two child screens, which is what lets the
 * suggestion list and the result list see the same query. [install] exists so the store wave can
 * swap in the real `StoreSearch` from `StoreStream` without touching any widget.
 */
object SearchStoreProvider {

    @Volatile
    private var instance: SearchStore? = null

    fun get(): SearchStore = instance ?: synchronized(this) {
        instance ?: DefaultSearchStore().also { instance = it }
    }

    fun install(searchStore: SearchStore) {
        synchronized(this) { instance = searchStore }
    }
}
