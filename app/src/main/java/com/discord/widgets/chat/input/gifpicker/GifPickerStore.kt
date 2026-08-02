package com.discord.widgets.chat.input.gifpicker

import com.discord.models.gifpicker.domain.ModelGifCategory
import com.discord.models.gifpicker.dto.ModelGif
import com.discord.stores.StoreStream
import java.util.Collections
import rx.Observable

/**
 * Everything the GIF picker needs from the Tenor-backed catalogue.
 *
 * The contract is declared here, in the feature package, exactly as [com.discord.widgets.stickers.StickerStore]
 * is — so that when `com.discord.stores.StoreGifPicker` is reconstructed it implements this
 * interface and [GifPickerStores.gifPicker] starts resolving to the real dispatcher-confined,
 * `ObservationDeck`-backed store with no change to any caller here.
 *
 * The reference's store API is reproduced one-for-one:
 * `observeGifCategories`, `observeTrendingGifCategoryPreviewUrl`, `observeTrendingCategoryGifs`,
 * `observeGifsForSearchQuery`, `observeGifTrendingSearchTerms`, `observeSuggestedSearchTerms`,
 * `fetchGifCategories`, plus the two "still loading" sentinel lists its search pipeline filters on.
 */
interface GifPickerStore {

    /** The category tiles shown on the picker's landing grid, without the trending tile. */
    fun observeGifCategories(): Observable<List<ModelGifCategory>>

    /** Preview image for the synthetic "Trending" tile; empty until the first fetch lands. */
    fun observeTrendingGifCategoryPreviewUrl(): Observable<String>

    /** The GIFs behind the "Trending" tile. */
    fun observeTrendingCategoryGifs(): Observable<List<ModelGif>>

    /**
     * Search results for [query], emitting [SEARCH_RESULTS_LOADING] first so callers can render a
     * loading state, then the results. Repeated queries are served from the LRU cache.
     */
    fun observeGifsForSearchQuery(query: String): Observable<List<ModelGif>>

    /** The terms offered when the search box is empty. */
    fun observeGifTrendingSearchTerms(): Observable<List<String>>

    /** "Related search" chips for [query]; emits [SEARCH_TERMS_LOADING] while in flight. */
    fun observeSuggestedSearchTerms(query: String): Observable<List<String>>

    /** Favorites in newest-first order. */
    fun observeFavoriteGifs(): Observable<List<ModelGif>>

    /** Frecency-ranked GIFs, bounded by the store. */
    fun observeFrequentlyUsedGifs(): Observable<List<ModelGif>>

    fun onGifUsed(gif: ModelGif)

    fun setFavoriteGif(gif: ModelGif, favorite: Boolean)

    /** Idempotent: a fetch already in flight is not reissued. */
    fun fetchGifCategories()

    companion object {
        /**
         * Identity sentinels meaning "the request for this key has not come back yet".
         *
         * **Deliberate divergence.** The reference declares both as `emptyList()`, which is the
         * `EmptyList` *singleton* — so its `results !== searchResultsLoadingList` filter also
         * rejects a genuinely empty result set, and a search with no hits never leaves the loading
         * spinner. Allocating distinct instances here keeps the identity test meaningful and lets
         * the "no GIFs found" state actually render.
         */
        val SEARCH_RESULTS_LOADING: List<ModelGif> = Collections.unmodifiableList(ArrayList(0))
        val SEARCH_TERMS_LOADING: List<String> = Collections.unmodifiableList(ArrayList(0))
    }
}

/**
 * The single resolution point for [GifPickerStore], mirroring `StickerStores`.
 *
 * A `val` rather than an installable mutable static: view models take the store as a defaulted
 * constructor parameter, so tests pass a fake instead of swapping a global.
 */
object GifPickerStores {
    val gifPicker: GifPickerStore by lazy(StoreStream::getGifPicker)
}
