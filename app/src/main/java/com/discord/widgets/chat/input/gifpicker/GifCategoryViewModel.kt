package com.discord.widgets.chat.input.gifpicker

import com.discord.app.AppViewModel
import com.discord.models.gifpicker.dto.ModelGif
import com.discord.stores.StoreAnalytics
import com.discord.stores.StoreMediaFavorites
import com.discord.utilities.analytics.SearchType
import com.discord.utilities.analytics.Traits
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.input.gifpicker.GifAdapterItem.GifItem
import rx.Observable

/**
 * Drives [WidgetGifCategory] — the grid behind one tapped category tile.
 *
 * A category is just a saved search: `Standard` re-issues the category name as a query, `Trending`
 * hits the dedicated trending endpoint. Both go through the same cache in [GifPickerStore], so
 * re-opening a category is instant.
 */
class GifCategoryViewModel(
    gifCategoryItem: GifCategoryItem,
    private val messageSender: GifMessageSender = GifMessageSender(),
    private val storeGifPicker: GifPickerStore = GifPickerStores.gifPicker,
    /**
     * `StoreAnalytics` exists but `StoreStream` publishes no accessor for it yet, so search
     * telemetry is off by default rather than routed through an invented seam. Pass the store when
     * one is available and every reference event fires unchanged.
     */
    private val storeAnalytics: StoreAnalytics? = null,
    storeStateObservable: Observable<StoreState> = observeStoreState(gifCategoryItem, storeGifPicker),
) : AppViewModel<GifCategoryViewModel.ViewState>() {

    data class StoreState(val gifs: List<ModelGif>, val favoriteKeys: Set<String>)

    data class ViewState(val gifItems: List<GifItem>) {
        /** Counted lazily; only the analytics path reads it. */
        val gifCount: Int by lazy { gifItems.size }
    }

    init {
        storeAnalytics?.trackSearchStarted(SearchType.GIF)
        storeStateObservable
            .restSubscribeOn(retry = false)
            .ui(this)
            .subscribe({ handleStoreState(it) }, { /* the loading grid stays; the store retries */ })
    }

    private fun handleStoreState(storeState: StoreState) {
        val viewState = ViewState(
            storeState.gifs.map { gif ->
                GifItem(
                    gif,
                    isFavorite = StoreMediaFavorites.canonicalGifKey(gif.tenorGifUrl) in storeState.favoriteKeys,
                )
            },
        )
        storeAnalytics?.trackSearchResultViewed(SearchType.GIF, viewState.gifCount)
        updateViewState(viewState)
    }

    fun selectGif(gifItem: GifItem) {
        viewState?.let { loaded ->
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

    companion object {
        fun observeStoreState(
            gifCategoryItem: GifCategoryItem,
            storeGifPicker: GifPickerStore = GifPickerStores.gifPicker,
        ): Observable<StoreState> {
            // The Favorites tab's own results are already the favorites list, so its star-icon keys
            // come straight from those results instead of paying for a second subscription (and a
            // second sort/allocation pass) against the exact same underlying data.
            if (gifCategoryItem is GifCategoryItem.Favorite) {
                return storeGifPicker.observeFavoriteGifs().map { results ->
                    StoreState(
                        results,
                        results.mapTo(HashSet()) { StoreMediaFavorites.canonicalGifKey(it.tenorGifUrl) },
                    )
                }
            }
            val gifs = when (gifCategoryItem) {
                is GifCategoryItem.Standard ->
                    storeGifPicker.observeGifsForSearchQuery(gifCategoryItem.gifCategory.categoryName)

                is GifCategoryItem.Trending -> storeGifPicker.observeTrendingCategoryGifs()
                is GifCategoryItem.Frequent -> storeGifPicker.observeFrequentlyUsedGifs()
                is GifCategoryItem.Favorite -> error("Favorite is handled above")
            }
            // Hold the loading grid until real results land, rather than flashing an empty one.
            return gifs
                .filter { it !== GifPickerStore.SEARCH_RESULTS_LOADING }
                .switchMap { results ->
                    storeGifPicker.observeFavoriteGifs().map { favorites ->
                        StoreState(
                            results,
                            favorites.mapTo(HashSet()) { StoreMediaFavorites.canonicalGifKey(it.tenorGifUrl) },
                        )
                    }
                }
        }
    }
}
