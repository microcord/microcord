package com.discord.widgets.chat.input.gifpicker

import com.discord.R
import com.discord.app.AppViewModel
import com.discord.models.gifpicker.dto.ModelGif
import com.discord.stores.StoreAnalytics
import com.discord.stores.StoreMediaFavorites
import com.discord.utilities.analytics.SearchType
import com.discord.utilities.analytics.Traits
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.input.gifpicker.GifAdapterItem.GifItem
import com.discord.widgets.chat.input.gifpicker.GifAdapterItem.SuggestedTermsItem
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject

/**
 * Drives [WidgetGifPickerSearch].
 *
 * Three states are genuinely distinct and all three are rendered:
 *  - **empty query** — the trending terms, presented as tappable chips over an empty grid;
 *  - **query in flight** — [ViewState.LoadingSearchResults], which clears the grid and flips the
 *    view flipper to the shimmer;
 *  - **results** — the GIFs, followed by either "related searches" (when there were hits) or the
 *    appropriate no-results copy (when there were not).
 *
 * Typing is throttled on the *leading* edge, so the first keystroke searches immediately and the
 * following second's worth of typing collapses into one more request.
 */
class GifSearchViewModel(
    private val searchSubject: BehaviorSubject<String> = BehaviorSubject.create(""),
    private val messageSender: GifMessageSender = GifMessageSender(),
    private val storeGifPicker: GifPickerStore = GifPickerStores.gifPicker,
    /** See [GifCategoryViewModel]: no `StoreStream` accessor for `StoreAnalytics` exists yet. */
    private val storeAnalytics: StoreAnalytics? = null,
    storeStateObservable: Observable<StoreState> =
        observeStoreState(searchSubject, storeAnalytics, storeGifPicker),
) : AppViewModel<GifSearchViewModel.ViewState>() {

    sealed class StoreState(val trendingSearchTerms: List<String>) {

        /** The search box is empty; only the trending terms are known. */
        data class TrendingSearchTermsResults(val trending: List<String>) : StoreState(trending)

        data class SearchResults(
            val gifs: List<ModelGif>,
            val suggested: List<String>,
            val trending: List<String>,
            val searchQuery: String?,
            val favoriteKeys: Set<String> = emptySet(),
        ) : StoreState(trending)
    }

    sealed class ViewState {
        object LoadingSearchResults : ViewState()

        data class Loaded(val adapterItems: List<GifAdapterItem>) : ViewState() {
            val gifCount: Int by lazy { adapterItems.count { it is GifItem } }
        }
    }

    init {
        storeStateObservable
            .ui(this)
            .subscribe({ handleStoreState(it) }, { /* leave the last rendered results in place */ })
    }

    private fun handleStoreState(storeState: StoreState) {
        val loaded = ViewState.Loaded(
            buildAdapterItems(storeState, hasQuery = searchSubject.value.orEmpty().isNotEmpty()),
        )
        if (storeState is StoreState.SearchResults) {
            storeAnalytics?.trackSearchResultViewed(SearchType.GIF, loaded.gifCount)
        }
        updateViewState(loaded)
    }

    fun selectGif(gifItem: GifItem) {
        (viewState as? ViewState.Loaded)?.let { loaded ->
            storeAnalytics?.trackSearchResultSelected(
                searchType = SearchType.GIF,
                totalResultsCount = loaded.gifCount,
                sourceTrait = Traits.Source(_object = Traits.Source.Obj.GIF_PICKER),
            )
        }
        if (messageSender.send(gifItem.gif.tenorGifUrl)) storeGifPicker.onGifUsed(gifItem.gif)
    }

    fun toggleFavorite(gifItem: GifItem) =
        storeGifPicker.setFavoriteGif(gifItem.gif, !gifItem.isFavorite)

    /**
     * The loading flip happens here rather than downstream because it must be immediate: the store
     * cannot report "in flight" until the throttle window has elapsed.
     */
    fun setSearchText(searchText: String) {
        if (searchSubject.value != searchText && searchText.isNotEmpty()) {
            updateViewState(ViewState.LoadingSearchResults)
        }
        searchSubject.onNext(searchText)
    }

    companion object {
        private const val SEARCH_THROTTLE_MS = 1000L

        /**
         * Turns one store state into the grid.
         *
         * The trailing chip block is always present and is what distinguishes the four outcomes:
         * results found; a query with no results and no suggestions; a query with no results but
         * suggestions to offer; and no query at all.
         *
         * @param hasQuery whether the search box currently holds text. It is passed in rather than
         *   read from [StoreState.SearchResults.searchQuery] because the store state can lag a
         *   keystroke behind the field, and the copy shown must match what the user sees.
         */
        fun buildAdapterItems(storeState: StoreState, hasQuery: Boolean): List<GifAdapterItem> {
            val items = mutableListOf<GifAdapterItem>()

            when (storeState) {
                is StoreState.SearchResults -> {
                    items.addAll(
                        storeState.gifs.map { gif ->
                            GifItem(
                                gif,
                                storeState.searchQuery,
                                StoreMediaFavorites.canonicalGifKey(gif.tenorGifUrl) in storeState.favoriteKeys,
                            )
                        },
                    )

                    val hasResults = items.isNotEmpty()
                    val hasSuggestions = storeState.suggested.isNotEmpty()

                    items += when {
                        hasResults -> SuggestedTermsItem.SuggestedTermsNonEmptyResults(
                            storeState.suggested,
                            R.string.gif_picker_related_search,
                        )

                        hasQuery && !hasSuggestions -> SuggestedTermsItem.SuggestedTermsEmptyResults(
                            storeState.suggested,
                            R.string.no_gif_search_results_without_related_search,
                        )

                        hasQuery -> SuggestedTermsItem.SuggestedTermsEmptyResults(
                            storeState.suggested,
                            R.string.no_gif_search_results_with_related_search,
                        )

                        else -> SuggestedTermsItem.SuggestedTermsEmptyResults(
                            storeState.suggested,
                            R.string.gif_picker_enter_search,
                        )
                    }
                }

                is StoreState.TrendingSearchTermsResults ->
                    items += SuggestedTermsItem.SuggestedTermsEmptyResults(
                        storeState.trendingSearchTerms,
                        R.string.gif_picker_enter_search,
                    )
            }
            return items
        }

        fun observeStoreState(
            searchSubject: BehaviorSubject<String>,
            storeAnalytics: StoreAnalytics? = null,
            storeGifPicker: GifPickerStore = GifPickerStores.gifPicker,
        ): Observable<StoreState> = storeGifPicker.observeGifTrendingSearchTerms().switchMap { trending ->
            observeQueryState(searchSubject, storeAnalytics, storeGifPicker, trending)
        }

        private fun observeQueryState(
            searchSubject: BehaviorSubject<String>,
            storeAnalytics: StoreAnalytics?,
            storeGifPicker: GifPickerStore,
            trendingTerms: List<String>,
        ): Observable<StoreState> = searchSubject
            .subscribeOn(Schedulers.computation())
            .distinctUntilChanged()
            .leadingEdgeThrottle(SEARCH_THROTTLE_MS, TimeUnit.MILLISECONDS)
            .doOnNext { storeAnalytics?.trackSearchStarted(SearchType.GIF) }
            .switchMap { query ->
                if (query.isEmpty()) {
                    Observable.just(StoreState.TrendingSearchTermsResults(trendingTerms))
                } else {
                    Observable.combineLatest(
                        storeGifPicker.observeGifsForSearchQuery(query)
                            .filter { it !== GifPickerStore.SEARCH_RESULTS_LOADING },
                        storeGifPicker.observeSuggestedSearchTerms(query)
                            .filter { it !== GifPickerStore.SEARCH_TERMS_LOADING },
                        storeGifPicker.observeFavoriteGifs(),
                    ) { gifs, terms, favorites ->
                        StoreState.SearchResults(
                            gifs,
                            terms,
                            trendingTerms,
                            query,
                            favorites.mapTo(HashSet()) {
                                StoreMediaFavorites.canonicalGifKey(it.tenorGifUrl)
                            },
                        )
                    }
                }
            }
    }
}
