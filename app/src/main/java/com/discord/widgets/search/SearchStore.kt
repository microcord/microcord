package com.discord.widgets.search

import com.discord.utilities.search.SearchTarget
import com.discord.utilities.search.network.state.SearchState
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.query.node.QueryNode
import com.discord.utilities.search.query.node.answer.HasAnswerOption
import com.discord.utilities.search.strings.SearchStringProvider
import com.discord.utilities.search.suggestion.entries.ChannelSuggestion
import com.discord.utilities.search.suggestion.entries.UserSuggestion
import com.discord.utilities.search.validation.SearchData
import rx.Observable

/**
 * The message-search store surface, as the search screens need it.
 *
 * **This is a seam, not a store.** The reference splits the same surface across four store classes —
 * `StoreSearch`, `StoreSearchInput`, `StoreSearchQuery` and `StoreSearchData` — none of which exist
 * in this project yet. Rather than build a competing store under `com.discord.stores` or ship a
 * dead screen, the search feature talks to these interfaces and [SearchStoreProvider] hands it a
 * working implementation. When the store wave lands the real stores, they implement these
 * interfaces (or the interfaces are deleted and the widgets call the stores directly) and
 * [SearchStoreProvider.install] is called once from `StoreStream`. Nothing else has to change.
 *
 * The split below is the reference's, member for member; see `docs/parity-audit/followups.md`.
 */
interface SearchStore {

    /** Which of the two search panes is in front: the suggestion list or the results list. */
    enum class DisplayState { SUGGESTIONS, RESULTS }

    val storeSearchInput: SearchInputStore
    val storeSearchQuery: SearchQueryStore
    val storeSearchData: SearchDataStore

    /** Binds the store to a guild. Idempotent for the same target. */
    fun initForGuild(guildId: Long, searchStringProvider: SearchStringProvider)

    /** Binds the store to a private channel. Idempotent for the same target. */
    fun initForChannel(channelId: Long, searchStringProvider: SearchStringProvider)

    fun getDisplayState(): Observable<DisplayState>

    /** Recent queries for the current target; empty until a target is bound. */
    fun getHistory(): Observable<Collection<List<QueryNode>>>

    /** Parses [queryString], records it in history, and runs the first page. */
    fun loadInitial(queryString: String, searchStringProvider: SearchStringProvider)

    /** Requests the page before [oldestMessageId]. No-op unless a completed page has more. */
    fun loadMore(oldestMessageId: Long)

    fun clearHistory()

    /** Releases the target and everything derived from it. Called when the screen is destroyed. */
    fun clear()
}

/**
 * The search box: raw text in, parsed AST out, plus the edits the suggestion rows perform on it.
 *
 * [currentParsedInput] is what the user typed; [forcedInput] is the store pushing a new AST *back*
 * into the box after a suggestion was tapped, which the screen renders and re-selects.
 */
interface SearchInputStore {
    val currentParsedInput: Observable<List<QueryNode>>
    val forcedInput: Observable<List<QueryNode>>

    /** True once the query contains anything that is not a bare filter keyword. */
    val isInputValid: Observable<Boolean>

    fun init(searchStringProvider: SearchStringProvider)
    fun updateInput(input: String)
    fun clear()

    fun onFilterClicked(
        filterType: FilterType,
        searchStringProvider: SearchStringProvider,
        query: MutableList<QueryNode>,
    )

    fun onFromUserClicked(
        userSuggestion: UserSuggestion,
        fromFilterString: CharSequence,
        query: MutableList<QueryNode>,
    )

    fun onMentionsUserClicked(
        userSuggestion: UserSuggestion,
        mentionsFilterString: CharSequence,
        query: MutableList<QueryNode>,
    )

    fun onInChannelClicked(
        channelSuggestion: ChannelSuggestion,
        inFilterString: CharSequence,
        query: MutableList<QueryNode>,
    )

    fun onHasClicked(
        hasAnswerOption: HasAnswerOption,
        hasFilterString: CharSequence,
        hasAnswerString: CharSequence,
        query: MutableList<QueryNode>,
    )

    /** Replays a whole query from history. */
    fun onQueryClicked(query: List<QueryNode>)
}

/** The in-flight request and the pages accumulated so far. */
interface SearchQueryStore {
    val state: Observable<SearchState>

    fun parseAndQuery(
        searchTarget: SearchTarget,
        queryString: String,
        searchStringProvider: SearchStringProvider,
        includeNsfw: Boolean,
        onQueryParsed: (List<QueryNode>) -> Unit,
    )

    fun loadMore(searchTarget: SearchTarget, oldestMessageId: Long)
    fun clear()
}

/** The names the current target lets a query refer to. */
interface SearchDataStore {
    fun get(): Observable<SearchData>
    fun init(searchTarget: SearchTarget)
    fun clear()
}
