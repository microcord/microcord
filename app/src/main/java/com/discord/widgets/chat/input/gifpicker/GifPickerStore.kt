package com.discord.widgets.chat.input.gifpicker

import com.discord.models.gifpicker.domain.ModelGifCategory
import com.discord.models.gifpicker.dto.ModelGif
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import java.util.Collections
import java.util.LinkedHashMap
import rx.Observable
import rx.subjects.BehaviorSubject

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
    /** Replace the right-hand side with `StoreStream.getGifPicker()` when that store lands. */
    val gifPicker: GifPickerStore by lazy { RestBackedGifPickerStore() }
}

/**
 * Stand-in for `com.discord.stores.StoreGifPicker`, backed directly by the five `gifs/` endpoints
 * that already exist in [RestAPIInterface] and currently have no caller.
 *
 * (Do not write a `*` wildcard after that path: Kotlin block comments nest, so a slash-star inside
 * a KDoc block opens a second comment and the closing delimiter below then closes only the inner
 * one, leaving the file unterminated to EOF. That cost a build once.)
 *
 * Faithful to the reference in what it exposes, in the fixed `provider=tenor` /
 * `media_format=tinygif` query parameters, in the fetch-on-first-observe behaviour, and in the
 * 20-entry LRU bound on both query caches. It deliberately does **not** reimplement store
 * machinery — no `Dispatcher` confinement, no `ObservationDeck` registration, no `snapshotData()`
 * split. Building that here would be a competing store; the point of this class is that the GIF
 * picker works today rather than rendering nothing, and that `StoreGifPicker` can replace it
 * wholesale.
 */
internal class RestBackedGifPickerStore(
    private val api: RestAPIInterface = RestAPI.api,
    private val locale: () -> String = { StoreStream.getUserSettingsSystem().getLocale() },
) : GifPickerStore {

    private val categories = BehaviorSubject.create<List<ModelGifCategory>>(emptyList())
    private val trendingPreviewUrl = BehaviorSubject.create("")
    private val trendingGifs = BehaviorSubject.create<List<ModelGif>>(emptyList())
    private val trendingSearchTerms = BehaviorSubject.create<List<String>>(emptyList())

    private val searchResults = BehaviorSubject.create<Map<String, List<ModelGif>>>(emptyMap())
    private val suggestedTerms = BehaviorSubject.create<Map<String, List<String>>>(emptyMap())

    private val searchCache = LruCache<String, List<ModelGif>>()
    private val suggestedCache = LruCache<String, List<String>>()

    private var fetchingCategories = false
    private var fetchingTrendingGifs = false
    private var fetchingTrendingTerms = false

    override fun observeGifCategories(): Observable<List<ModelGifCategory>> {
        if (categories.value.isEmpty() && !fetchingCategories) fetchGifCategories()
        return categories
    }

    override fun observeTrendingGifCategoryPreviewUrl(): Observable<String> {
        if (categories.value.isEmpty() && !fetchingCategories) fetchGifCategories()
        return trendingPreviewUrl
    }

    override fun observeTrendingCategoryGifs(): Observable<List<ModelGif>> {
        if (trendingGifs.value.isEmpty() && !fetchingTrendingGifs) fetchTrendingCategoryGifs()
        return trendingGifs
    }

    override fun observeGifTrendingSearchTerms(): Observable<List<String>> {
        if (trendingSearchTerms.value.isEmpty() && !fetchingTrendingTerms) fetchTrendingSearchTerms()
        return trendingSearchTerms
    }

    override fun observeGifsForSearchQuery(query: String): Observable<List<ModelGif>> {
        if (!searchCache.containsKey(query)) {
            put(searchCache, searchResults, query, GifPickerStore.SEARCH_RESULTS_LOADING)
            api.getGifSearchResults(query, PROVIDER, locale(), MEDIA_FORMAT, SEARCH_LIMIT)
                .restSubscribeOn(retry = false)
                .subscribe(
                    { gifs -> put(searchCache, searchResults, query, gifs.map(ModelGif::createFromGifDto)) },
                    { put(searchCache, searchResults, query, emptyList()) },
                )
        }
        return searchResults.map { it[query] }.filter { it != null }.map { it!! }
    }

    override fun observeSuggestedSearchTerms(query: String): Observable<List<String>> {
        if (!suggestedCache.containsKey(query)) {
            put(suggestedCache, suggestedTerms, query, GifPickerStore.SEARCH_TERMS_LOADING)
            api.getGifSuggestedSearchTerms(PROVIDER, query, locale(), TERMS_LIMIT)
                .restSubscribeOn(retry = false)
                .subscribe(
                    { terms -> put(suggestedCache, suggestedTerms, query, terms) },
                    { put(suggestedCache, suggestedTerms, query, emptyList()) },
                )
        }
        return suggestedTerms.map { it[query] }.filter { it != null }.map { it!! }
    }

    override fun fetchGifCategories() {
        if (fetchingCategories) return
        fetchingCategories = true
        api.getTrendingGifCategories(PROVIDER, locale(), MEDIA_FORMAT)
            .restSubscribeOn(retry = false)
            .subscribe(
                { response ->
                    fetchingCategories = false
                    categories.onNext(
                        response.categories.map { ModelGifCategory(it.name, it.src) },
                    )
                    trendingPreviewUrl.onNext(response.gifs.firstOrNull()?.src.orEmpty())
                },
                {
                    // Leave the categories empty: the picker keeps showing its loading grid rather
                    // than an empty one it cannot explain, and the next observe retries.
                    fetchingCategories = false
                },
            )
    }

    private fun fetchTrendingCategoryGifs() {
        fetchingTrendingGifs = true
        api.getTrendingGifCategory(PROVIDER, locale(), MEDIA_FORMAT, SEARCH_LIMIT)
            .restSubscribeOn(retry = false)
            .subscribe(
                { gifs ->
                    fetchingTrendingGifs = false
                    trendingGifs.onNext(gifs.map(ModelGif::createFromGifDto))
                },
                { fetchingTrendingGifs = false },
            )
    }

    private fun fetchTrendingSearchTerms() {
        fetchingTrendingTerms = true
        api.getGifTrendingSearchTerms(PROVIDER, locale(), TERMS_LIMIT)
            .restSubscribeOn(retry = false)
            .subscribe(
                { terms ->
                    fetchingTrendingTerms = false
                    trendingSearchTerms.onNext(terms)
                },
                { fetchingTrendingTerms = false },
            )
    }

    private fun <V> put(
        cache: LruCache<String, V>,
        subject: BehaviorSubject<Map<String, V>>,
        key: String,
        value: V,
    ) {
        cache[key] = value
        subject.onNext(LinkedHashMap(cache))
    }

    /** The reference's `CacheHistory`: a `LinkedHashMap` that drops its eldest entry past 20. */
    private class LruCache<K, V> : LinkedHashMap<K, V>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > MAX_ENTRIES

        private companion object {
            const val MAX_ENTRIES = 20
        }
    }

    private companion object {
        const val PROVIDER = "tenor"
        const val MEDIA_FORMAT = "tinygif"
        const val SEARCH_LIMIT = 50
        const val TERMS_LIMIT = 5
    }
}
