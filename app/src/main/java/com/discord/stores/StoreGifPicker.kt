package com.discord.stores

import com.discord.models.gifpicker.domain.ModelGifCategory
import com.discord.models.gifpicker.dto.ModelGif
import com.discord.models.gifpicker.dto.TrendingGifCategoriesResponseDto
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.media.ExpressionFrecencyTracker
import com.discord.utilities.persister.Persister
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import com.discord.widgets.chat.input.gifpicker.GifPickerStore
import java.util.Collections
import java.util.LinkedHashMap
import rx.Observable

class StoreGifPicker(
    private val dispatcher: Dispatcher,
    private val storeUserSettingsSystem: StoreUserSettingsSystem,
    private val restAPI: () -> RestAPIInterface? = { RestAPI.apiOrNull },
    private val observationDeck: ObservationDeck,
    private val mediaFavorites: StoreMediaFavorites,
    private val frecencyCache: Persister<ExpressionFrecencyTracker> =
        Persister(GIF_FRECENCY_CACHE_KEY, ExpressionFrecencyTracker()),
    private val gifHistoryCache: Persister<Map<String, ModelGif>> =
        Persister(GIF_HISTORY_CACHE_KEY, emptyMap()),
) : StoreV2(), GifPickerStore {
    private var frecency = frecencyCache.get()
    private val gifHistory = gifHistoryCache.get().toMutableMap()
    private var frequentlyUsedGifsSnapshot: List<ModelGif> = buildFrequentlyUsedGifs()
    private val gifCategories = ArrayList<ModelGifCategory>()
    private var gifCategoriesSnapshot: List<ModelGifCategory> = emptyList()
    private val gifSearchHistory = CacheHistory<String, List<ModelGif>>()
    private var gifSearchHistorySnapshot: Map<String, List<ModelGif>> = emptyMap()
    private val gifSuggestedSearchTermsHistory = CacheHistory<String, List<String>>()
    private var suggestedSearchTermsSnapshot: Map<String, List<String>> = emptyMap()
    private val gifTrendingSearchTerms = ArrayList<String>()
    private var gifTrendingSearchTermsSnapshot: List<String> = emptyList()
    private val trendingCategoryGifs = ArrayList<ModelGif>()
    private var trendingCategoryGifsSnapshot: List<ModelGif> = emptyList()
    private var trendingGifCategoryPreviewUrl = ""
    private var isFetchingGifCategories = false
    private var isFetchingTrendingCategoryGifs = false
    private var isFetchingTrendingSearchTerms = false

    override fun observeGifCategories(): Observable<List<ModelGifCategory>> {
        dispatcher.schedule {
            if (gifCategories.isEmpty() && !isFetchingGifCategories) fetchGifCategoriesInternal()
        }
        return observationDeck.connectRx(this) { gifCategoriesSnapshot }
    }

    override fun observeTrendingGifCategoryPreviewUrl(): Observable<String> =
        observationDeck.connectRx(this) { trendingGifCategoryPreviewUrl }

    override fun observeTrendingCategoryGifs(): Observable<List<ModelGif>> {
        dispatcher.schedule {
            if (trendingCategoryGifs.isEmpty() && !isFetchingTrendingCategoryGifs) fetchTrendingCategoryGifs()
        }
        return observationDeck.connectRx(this) { trendingCategoryGifsSnapshot }
    }

    override fun observeGifsForSearchQuery(query: String): Observable<List<ModelGif>> {
        dispatcher.schedule {
            if (!gifSearchHistory.containsKey(query)) {
                gifSearchHistory[query] = searchResultsLoadingList
                markChanged()
                fetchGifsForSearchQuery(query)
            }
        }
        return observationDeck.connectRx(this) { gifSearchHistorySnapshot }
            .map { it[query] }
            .filter { it != null }
            .map { it!! }
    }

    override fun observeGifTrendingSearchTerms(): Observable<List<String>> {
        dispatcher.schedule {
            if (gifTrendingSearchTerms.isEmpty() && !isFetchingTrendingSearchTerms) fetchGifTrendingSearchTerms()
        }
        return observationDeck.connectRx(this) { gifTrendingSearchTermsSnapshot }
    }

    override fun observeSuggestedSearchTerms(query: String): Observable<List<String>> {
        dispatcher.schedule {
            if (!gifSuggestedSearchTermsHistory.containsKey(query)) {
                gifSuggestedSearchTermsHistory[query] = searchTermsLoadingList
                markChanged()
                fetchSuggestedSearchTerms(query)
            }
        }
        return observationDeck.connectRx(this) { suggestedSearchTermsSnapshot }
            .map { it[query] }
            .filter { it != null }
            .map { it!! }
    }

    override fun observeFavoriteGifs(): Observable<List<ModelGif>> =
        mediaFavorites.observeFavoriteGifs().map { favorites -> favorites.map { it.toModelGif() } }

    override fun observeFrequentlyUsedGifs(): Observable<List<ModelGif>> =
        observationDeck.connectRx(this) { frequentlyUsedGifsSnapshot }.distinctUntilChanged()

    override fun onGifUsed(gif: ModelGif) = dispatcher.schedule {
        val key = StoreMediaFavorites.canonicalGifKey(gif.tenorGifUrl)
        frecency.track(key)
        gifHistory[key] = gif.copy(tenorGifUrl = key)
        markChanged()
    }

    override fun setFavoriteGif(gif: ModelGif, favorite: Boolean) =
        mediaFavorites.setFavoriteGif(gif, favorite)

    override fun fetchGifCategories() = dispatcher.schedule {
        if (!isFetchingGifCategories) fetchGifCategoriesInternal()
    }

    @StoreThread
    private fun fetchGifCategoriesInternal() {
        val api = restAPI() ?: return
        isFetchingGifCategories = true
        api.getTrendingGifCategories(PROVIDER, storeUserSettingsSystem.getLocale(), MEDIA_FORMAT)
            .restSubscribeOn(retry = false)
            .subscribe(
                { response -> dispatcher.schedule {
                    handleTrendingCategoriesResponse(response)
                    isFetchingGifCategories = false
                } },
                { dispatcher.schedule { isFetchingGifCategories = false } },
            )
    }

    @StoreThread
    private fun fetchTrendingCategoryGifs() {
        val api = restAPI() ?: return
        isFetchingTrendingCategoryGifs = true
        api.getTrendingGifCategory(PROVIDER, storeUserSettingsSystem.getLocale(), MEDIA_FORMAT, SEARCH_PAGE_SIZE)
            .restSubscribeOn(retry = false)
            .subscribe(
                { gifs -> dispatcher.schedule {
                    trendingCategoryGifs.clear()
                    trendingCategoryGifs += gifs.map(ModelGif::createFromGifDto)
                    isFetchingTrendingCategoryGifs = false
                    markChanged()
                } },
                { dispatcher.schedule { isFetchingTrendingCategoryGifs = false } },
            )
    }

    @StoreThread
    private fun fetchGifTrendingSearchTerms() {
        val api = restAPI() ?: return
        isFetchingTrendingSearchTerms = true
        api.getGifTrendingSearchTerms(PROVIDER, storeUserSettingsSystem.getLocale(), SEARCH_TERM_LIMIT)
            .restSubscribeOn(retry = false)
            .subscribe(
                { terms -> dispatcher.schedule {
                    gifTrendingSearchTerms.clear()
                    gifTrendingSearchTerms += terms
                    isFetchingTrendingSearchTerms = false
                    markChanged()
                } },
                { dispatcher.schedule { isFetchingTrendingSearchTerms = false } },
            )
    }

    @StoreThread
    private fun fetchGifsForSearchQuery(query: String) {
        val api = restAPI()
        if (api == null) {
            handleGifSearchResults(query, emptyList())
            return
        }
        api.getGifSearchResults(query, PROVIDER, storeUserSettingsSystem.getLocale(), MEDIA_FORMAT, SEARCH_PAGE_SIZE)
            .restSubscribeOn(retry = false)
            .subscribe(
                { gifs -> dispatcher.schedule { handleGifSearchResults(query, gifs.map(ModelGif::createFromGifDto)) } },
                { dispatcher.schedule { handleGifSearchResults(query, emptyList()) } },
            )
    }

    @StoreThread
    private fun fetchSuggestedSearchTerms(query: String) {
        val api = restAPI()
        if (api == null) {
            handleSuggestedSearchTerms(query, emptyList())
            return
        }
        api.getGifSuggestedSearchTerms(PROVIDER, query, storeUserSettingsSystem.getLocale(), SEARCH_TERM_LIMIT)
            .restSubscribeOn(retry = false)
            .subscribe(
                { terms -> dispatcher.schedule { handleSuggestedSearchTerms(query, terms) } },
                { dispatcher.schedule { handleSuggestedSearchTerms(query, emptyList()) } },
            )
    }

    @StoreThread
    private fun handleTrendingCategoriesResponse(response: TrendingGifCategoriesResponseDto) {
        gifCategories.clear()
        gifCategories += response.categories.map { ModelGifCategory(it.name, it.src) }
        trendingGifCategoryPreviewUrl = response.gifs.firstOrNull()?.src.orEmpty()
        markChanged()
    }

    @StoreThread
    private fun handleGifSearchResults(query: String, gifs: List<ModelGif>) {
        gifSearchHistory[query] = gifs
        markChanged()
    }

    @StoreThread
    private fun handleSuggestedSearchTerms(query: String, terms: List<String>) {
        gifSuggestedSearchTermsHistory[query] = terms
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        gifCategoriesSnapshot = ArrayList(gifCategories)
        gifSearchHistorySnapshot = HashMap(gifSearchHistory)
        suggestedSearchTermsSnapshot = HashMap(gifSuggestedSearchTermsHistory)
        gifTrendingSearchTermsSnapshot = ArrayList(gifTrendingSearchTerms)
        trendingCategoryGifsSnapshot = ArrayList(trendingCategoryGifs)
        frequentlyUsedGifsSnapshot = buildFrequentlyUsedGifs()
        frecencyCache.set(frecency, persist = true)
        gifHistoryCache.set(HashMap(gifHistory), persist = true)
    }

    @StoreThread
    fun handlePreLogout() {
        frecencyCache.clear(persist = true)
        gifHistoryCache.clear(persist = true)
        frecency = ExpressionFrecencyTracker()
        gifHistory.clear()
        frequentlyUsedGifsSnapshot = emptyList()
        markChanged()
    }

    private fun buildFrequentlyUsedGifs(): List<ModelGif> = frecency.getSortedKeys()
        .mapNotNull(gifHistory::get)
        .take(MAX_FREQUENT_GIFS)

    private class CacheHistory<K, V> : LinkedHashMap<K, V>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > MAX_CACHE_ENTRIES
    }

    companion object {
        // ponytail: distinct identity sentinels preserve zero-result states; remove only to reproduce the reference spinner bug.
        @JvmField val searchResultsLoadingList: List<ModelGif> = Collections.unmodifiableList(ArrayList<ModelGif>(0))
        @JvmField val searchTermsLoadingList: List<String> = Collections.unmodifiableList(ArrayList<String>(0))

        private const val PROVIDER = "tenor"
        private const val MEDIA_FORMAT = "tinygif"
        private const val SEARCH_PAGE_SIZE = 50
        private const val SEARCH_TERM_LIMIT = 5
        private const val MAX_CACHE_ENTRIES = 20
        private const val MAX_FREQUENT_GIFS = 32

        const val GIF_FRECENCY_CACHE_KEY = "GIF_FRECENTS_V1"
        const val GIF_HISTORY_CACHE_KEY = "GIF_HISTORY_ITEMS_V1"
    }
}
