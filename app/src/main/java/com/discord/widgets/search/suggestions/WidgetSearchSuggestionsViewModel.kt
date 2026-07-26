package com.discord.widgets.search.suggestions

import com.discord.app.AppViewModel
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.search.query.node.QueryNode
import com.discord.utilities.search.query.node.QueryNode.Preprocessor.preprocess
import com.discord.utilities.search.strings.SearchStringProvider
import com.discord.utilities.search.suggestion.SearchSuggestionEngine
import com.discord.utilities.search.suggestion.entries.ChannelSuggestion
import com.discord.utilities.search.suggestion.entries.FilterSuggestion
import com.discord.utilities.search.suggestion.entries.HasSuggestion
import com.discord.utilities.search.suggestion.entries.RecentQuerySuggestion
import com.discord.utilities.search.suggestion.entries.SearchSuggestion
import com.discord.utilities.search.suggestion.entries.UserSuggestion
import com.discord.widgets.search.SearchStore
import com.discord.widgets.search.SearchStoreProvider
import rx.Observable

/**
 * Suggestions for the query as it stands, already grouped into adapter rows.
 *
 * [ViewState.query] is handed back to the store when a row is tapped: the store rewrites *that*
 * list rather than re-deriving it, which is what makes a suggestion replace the token being typed
 * instead of appending to the end.
 */
class WidgetSearchSuggestionsViewModel(
    private val searchStringProvider: SearchStringProvider,
    searchStore: SearchStore = SearchStoreProvider.get(),
) : AppViewModel<WidgetSearchSuggestionsViewModel.ViewState>() {

    data class ViewState(
        val query: MutableList<QueryNode>,
        val suggestionEntries: List<SearchSuggestion>,
    ) {
        /** Suggestions plus a header wherever the category changes. */
        val suggestionItems: List<MGRecyclerDataPayload> = buildList {
            var previousCategory: SearchSuggestion.Category? = null
            suggestionEntries.forEach { suggestion ->
                if (suggestion.category != previousCategory) {
                    add(WidgetSearchSuggestionsAdapter.getHeaderItem(suggestion.category))
                    previousCategory = suggestion.category
                }
                when (suggestion) {
                    is FilterSuggestion -> add(WidgetSearchSuggestionsAdapter.getFilterItem(suggestion))
                    is UserSuggestion -> add(WidgetSearchSuggestionsAdapter.getUserItem(suggestion))
                    is HasSuggestion -> add(WidgetSearchSuggestionsAdapter.getHasItem(suggestion))
                    is ChannelSuggestion -> add(WidgetSearchSuggestionsAdapter.getInChannelItem(suggestion))
                    is RecentQuerySuggestion ->
                        add(WidgetSearchSuggestionsAdapter.getRecentQueryItem(suggestion))
                    else -> Unit
                }
            }
        }
    }

    init {
        Observable
            .combineLatest(
                searchStore.storeSearchData.get(),
                searchStore.storeSearchInput.currentParsedInput,
                searchStore.getHistory(),
            ) { searchData, queryNodes, history ->
                val query = queryNodes.toMutableList()
                query.preprocess(searchData)
                ViewState(
                    query,
                    SearchSuggestionEngine.getSuggestions(
                        query,
                        searchData,
                        searchStringProvider,
                        history,
                    ),
                )
            }
            .computationLatest()
            .distinctUntilChanged()
            .ui(this)
            .subscribe(::updateViewState)
    }
}
