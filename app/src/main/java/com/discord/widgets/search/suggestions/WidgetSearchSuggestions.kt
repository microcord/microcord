package com.discord.widgets.search.suggestions

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.ui
import com.discord.utilities.search.strings.ContextSearchStringProvider
import com.discord.utilities.search.strings.SearchStringProvider
import com.discord.utilities.search.suggestion.entries.UserSuggestion
import com.discord.widgets.search.SearchStore
import com.discord.widgets.search.SearchStoreProvider

/**
 * The suggestion half of the search screen.
 *
 * Every row edits the query rather than running it: tapping a suggestion writes the corresponding
 * nodes into the AST and hands it back to the search box, which is why each callback carries the
 * current query along with the tapped suggestion.
 */
class WidgetSearchSuggestions : AppFragment(R.layout.widget_search_suggestions) {

    private lateinit var adapter: WidgetSearchSuggestionsAdapter
    private lateinit var searchStringProvider: SearchStringProvider

    private val searchStore: SearchStore get() = SearchStoreProvider.get()

    private val viewModel: WidgetSearchSuggestionsViewModel by appViewModels {
        WidgetSearchSuggestionsViewModel(ContextSearchStringProvider(requireContext()))
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val recycler = view.findViewById<RecyclerView>(R.id.search_suggestions_recycler)
        adapter = MGRecyclerAdapter.configure(WidgetSearchSuggestionsAdapter(recycler))
        // The list is rebuilt on nearly every keystroke; cross-fading each rebuild reads as flicker.
        if (StoreStream.getAccessibility().isReducedMotionEnabled()) recycler.itemAnimator = null
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        searchStringProvider = ContextSearchStringProvider(requireContext())
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
    }

    private fun configureUI(viewState: WidgetSearchSuggestionsViewModel.ViewState) {
        adapter.setData(viewState.suggestionItems)

        val input = searchStore.storeSearchInput
        val query = viewState.query

        adapter.onFilterClicked = { filterType ->
            input.onFilterClicked(filterType, searchStringProvider, query)
        }
        adapter.onUserClicked = { userSuggestion ->
            when (userSuggestion.targetType) {
                // A `from:` row also seeds the mentions filter, matching the reference: the two
                // filters share one suggestion list and the user is more often after both.
                UserSuggestion.TargetType.FROM -> {
                    input.onFromUserClicked(
                        userSuggestion,
                        searchStringProvider.fromFilterString,
                        query,
                    )
                    input.onMentionsUserClicked(
                        userSuggestion,
                        searchStringProvider.mentionsFilterString,
                        query,
                    )
                }
                UserSuggestion.TargetType.MENTIONS -> input.onMentionsUserClicked(
                    userSuggestion,
                    searchStringProvider.mentionsFilterString,
                    query,
                )
            }
        }
        adapter.onChannelClicked = { channelSuggestion ->
            input.onInChannelClicked(channelSuggestion, searchStringProvider.inFilterString, query)
        }
        adapter.onHasClicked = { hasAnswerOption ->
            input.onHasClicked(
                hasAnswerOption,
                searchStringProvider.hasFilterString,
                hasAnswerOption.getLocalizedInputText(searchStringProvider),
                query,
            )
        }
        adapter.onRecentQueryClicked = input::onQueryClicked
        adapter.onClearHistoryClicked = searchStore::clearHistory
    }
}
