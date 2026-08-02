package com.discord.stores

import android.content.SharedPreferences
import com.discord.models.domain.ModelSearchResponse
import com.discord.models.gifpicker.dto.GifCategoryDto
import com.discord.models.gifpicker.dto.GifDto
import com.discord.models.gifpicker.dto.TrendingGifCategoriesResponseDto
import com.discord.models.gifpicker.dto.TrendingGifPreviewDto
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.utilities.search.SearchTarget
import com.discord.utilities.search.network.SearchFetcher
import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.network.state.QueryFetchState
import com.discord.utilities.search.strings.SearchStringProvider
import com.discord.widgets.chat.input.expression.ExpressionTrayTab
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rx.Observable
import rx.schedulers.TestScheduler
import rx.subjects.ReplaySubject

class SearchGifExpressionStoresTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var stream: StoreStream

    @Before
    fun setUp() {
        val preferences = memoryPreferences()
        stream = StoreStream.initializeForTesting(
            temporaryFolder.newFolder(),
            preferences,
            preferences,
            storeTestScheduler("search-gif-expression-store-test"),
        )
    }

    @Test
    fun searchPaginationDisablesDuplicateLoadsUntilThePageReturns() {
        val fetcher = RecordingSearchFetcher()
        val query = StoreSearchQuery(fetcher)
        val states = CopyOnWriteArrayList<QueryFetchState>()
        query.state.map { it.queryFetchState }.subscribe(states::add)
        val data = StoreSearchData(
            stream.observationDeck,
            StoreStream.getChannels(),
            StoreStream.getUsers(),
            StoreStream.getGuilds(),
        )

        query.parseAndQuery(
            data,
            StoreSearch.SearchTarget(StoreSearch.SearchTarget.Type.CHANNEL, 7L),
            "hello",
            SEARCH_STRINGS,
            includeNsfw = false,
            onQueryParsed = {},
        )
        repeat(100) {
            if (states.lastOrNull() == QueryFetchState.COMPLETED) return@repeat
            Thread.sleep(5)
        }
        query.loadMore(StoreSearch.SearchTarget(StoreSearch.SearchTarget.Type.CHANNEL, 7L), 99L)
        query.loadMore(StoreSearch.SearchTarget(StoreSearch.SearchTarget.Type.CHANNEL, 7L), 98L)

        assertEquals(listOf<Long?>(null, 99L), fetcher.oldestMessageIds)
    }

    @Test
    fun searchInputRequiresMoreThanABareFilter() {
        val input = StoreSearchInput()
        val validity = CopyOnWriteArrayList<Boolean>()
        input.isInputValid.subscribe(validity::add)
        input.init(SEARCH_STRINGS)
        Thread.sleep(120)

        input.updateInput("hello")
        repeat(100) {
            if (validity.lastOrNull() == true) return@repeat
            Thread.sleep(5)
        }

        assertEquals(true, validity.last())
    }

    @Test
    fun gifStorePublishesLoadingThenARealEmptyResult() {
        val deck = ObservationDeck()
        val dispatcher = Dispatcher(storeTestScheduler("gif-store-test"))
        val searchResults = ReplaySubject.create<List<GifDto>>()
        val favorites = StoreMediaFavorites(deck, dispatcher)
        val store = StoreGifPicker(
            dispatcher,
            StoreStream.getUserSettingsSystem(),
            restAPI = { gifApi(searchResults) },
            observationDeck = deck,
            mediaFavorites = favorites,
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store, favorites), deck))
        val values = CopyOnWriteArrayList<List<com.discord.models.gifpicker.dto.ModelGif>>()

        store.observeGifsForSearchQuery("none").subscribe(values::add)
        assertSame(StoreGifPicker.searchResultsLoadingList, values.single())
        searchResults.onNext(emptyList())
        repeat(100) {
            if (values.size > 1) return@repeat
            Thread.sleep(5)
        }

        assertSame(StoreGifPicker.searchResultsLoadingList, values.first())
        assertEquals(emptyList<com.discord.models.gifpicker.dto.ModelGif>(), values.last())
        assertNotSame(StoreGifPicker.searchResultsLoadingList, values.last())
    }

    @Test
    fun gifFrecentsAreCanonicalAndBoundedToThirtyTwo() {
        val deck = ObservationDeck()
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val preferences = memoryPreferences()
        val settings = StoreUserSettingsSystem({}, dispatcher, deck, preferences, preferences).apply { restore() }
        val favorites = StoreMediaFavorites(deck, dispatcher)
        val store = StoreGifPicker(
            dispatcher,
            settings,
            restAPI = { null },
            observationDeck = deck,
            mediaFavorites = favorites,
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store, favorites), deck))

        repeat(40) { index ->
            store.onGifUsed(
                com.discord.models.gifpicker.dto.ModelGif(
                    "https://img/$index.gif",
                    "https%3A%2F%2Ftenor.com%2F$index",
                    100,
                    50,
                ),
            )
        }
        scheduler.triggerActions()

        val frequent = store.observeFrequentlyUsedGifs().toBlocking().first()
        assertEquals(32, frequent.size)
        assertTrue(frequent.all { it.tenorGifUrl.startsWith("https://tenor.com/") })
    }

    @Test
    fun expressionStoresPersistSelectionAndResetSuggestionsOnChannelChange() {
        val deck = ObservationDeck()
        val dispatcher = Dispatcher(storeTestScheduler("expression-store-test"))
        val navigation = StoreExpressionPickerNavigation(
            deck,
            dispatcher,
            Persister("TEST_EXPRESSION_TAB", ExpressionTrayTab.EMOJI),
        )
        val suggestions = StoreExpressionSuggestions(deck, dispatcher)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(navigation, suggestions), deck))
        val tabs = CopyOnWriteArrayList<ExpressionTrayTab>()
        val enabled = CopyOnWriteArrayList<Boolean>()
        navigation.observeSelectedTab().subscribe(tabs::add)
        suggestions.observeSuggestionsEnabled().subscribe(enabled::add)

        navigation.onSelectTab(ExpressionTrayTab.GIF)
        suggestions.setExpressionSuggestionsEnabled(false)
        assertEquals(ExpressionTrayTab.GIF, tabs.last())
        assertFalse(enabled.last())

        dispatcher.schedule { suggestions.handleChannelSelected() }
        assertEquals(true, enabled.last())
        assertEquals(ExpressionTrayTab.GIF, Persister("TEST_EXPRESSION_TAB", ExpressionTrayTab.EMOJI).get())
    }

    @Test
    fun storeStreamPublishesTheCluster() {
        assertSame(StoreStream.getSearch(), StoreStream.getSearch())
        assertSame(StoreStream.getGifPicker(), StoreStream.getGifPicker())
        assertSame(StoreStream.getExpressionPickerNavigation(), StoreStream.getExpressionPickerNavigation())
        assertSame(StoreStream.getExpressionSuggestions(), StoreStream.getExpressionSuggestions())
    }

    private class RecordingSearchFetcher : SearchFetcher() {
        val oldestMessageIds = CopyOnWriteArrayList<Long?>()

        override fun makeQuery(
            searchTarget: SearchTarget,
            oldestMessageId: Long?,
            searchQuery: SearchQuery,
        ): Observable<ModelSearchResponse> {
            oldestMessageIds += oldestMessageId
            return if (oldestMessageId == null) {
                Observable.just(ModelSearchResponse(totalResults = 1, messages = emptyList()))
            } else {
                Observable.never()
            }
        }
    }

    private fun gifApi(
        searchResults: Observable<List<GifDto>> = Observable.just(emptyList()),
    ): RestAPIInterface = Proxy.newProxyInstance(
        RestAPIInterface::class.java.classLoader,
        arrayOf(RestAPIInterface::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getTrendingGifCategories" -> Observable.just(
                TrendingGifCategoriesResponseDto(
                    listOf(GifCategoryDto("Cats", "cat-preview")),
                    listOf(TrendingGifPreviewDto("trending-preview")),
                ),
            )
            "getTrendingGifCategory" -> Observable.just(emptyList<GifDto>())
            "getGifSearchResults" -> searchResults
            "getGifTrendingSearchTerms", "getGifSuggestedSearchTerms" -> Observable.just(emptyList<String>())
            else -> error("Unexpected REST call: ${method.name}")
        }
    } as RestAPIInterface

    private fun memoryPreferences(): SharedPreferences {
        val values = HashMap<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString", "putBoolean", "putInt", "putLong", "putFloat", "putStringSet" -> {
                    values[args!![0] as String] = args[1]
                    editor
                }
                "remove" -> { values.remove(args!![0] as String); editor }
                "clear" -> { values.clear(); editor }
                "commit" -> true
                "apply" -> Unit
                else -> editor
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            val key = args?.firstOrNull() as? String
            when (method.name) {
                "edit" -> editor
                "contains" -> values.containsKey(key)
                "getAll" -> values.toMap()
                "getString", "getBoolean", "getInt", "getLong", "getFloat", "getStringSet" -> values[key] ?: args!![1]
                else -> null
            }
        } as SharedPreferences
    }

    private companion object {
        val SEARCH_STRINGS = object : SearchStringProvider {
            override val fromFilterString = "from:"
            override val inFilterString = "in:"
            override val mentionsFilterString = "mentions:"
            override val hasFilterString = "has:"
            override val linkAnswerString = "link"
            override val embedAnswerString = "embed"
            override val fileAnswerString = "file"
            override val videoAnswerString = "video"
            override val imageAnswerString = "image"
            override val soundAnswerString = "sound"
            override val stickerAnswerString = "sticker"
        }
    }
}
