package com.discord.widgets.chat.input.gifpicker

import com.discord.models.gifpicker.dto.ModelGif
import com.discord.models.gifpicker.domain.ModelGifCategory
import com.discord.utilities.feature.GifProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.android.plugins.RxAndroidPlugins
import rx.android.plugins.RxAndroidSchedulersHook
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject

class GifSearchViewModelTest {

    private val gif = ModelGif("https://img/1.gif", "https://tenor/1", 100, 50)
    private val search = BehaviorSubject.create("")
    private val states = PublishSubject.create<GifSearchViewModel.StoreState>()
    private val store = object : GifPickerStore {
        var provider: GifProvider? = null
        override fun observeGifCategories() = Observable.just(emptyList<ModelGifCategory>())
        override fun observeTrendingGifCategoryPreviewUrl() = Observable.just("")
        override fun observeTrendingCategoryGifs() = Observable.just(emptyList<ModelGif>())
        override fun observeGifsForSearchQuery(query: String) = Observable.just(emptyList<ModelGif>())
        override fun observeGifTrendingSearchTerms() = Observable.just(emptyList<String>())
        override fun observeSuggestedSearchTerms(query: String) = Observable.just(emptyList<String>())
        override fun observeFavoriteGifs() = Observable.just(emptyList<ModelGif>())
        override fun observeFrequentlyUsedGifs() = Observable.just(emptyList<ModelGif>())
        override fun onGifUsed(gif: ModelGif) = Unit
        override fun setFavoriteGif(gif: ModelGif, favorite: Boolean) = Unit
        override fun fetchGifCategories() = Unit
        override fun setProvider(provider: GifProvider, activeQuery: String?) {
            this.provider = provider
        }
    }

    @Before
    fun setUpMainScheduler() {
        RxAndroidPlugins.getInstance().reset()
        RxAndroidPlugins.getInstance().registerSchedulersHook(
            object : RxAndroidSchedulersHook() {
                override fun getMainThreadScheduler() = Schedulers.immediate()
            },
        )
    }

    @After
    fun resetMainScheduler() {
        RxAndroidPlugins.getInstance().reset()
    }

    private fun viewModel() = GifSearchViewModel(search, storeGifPicker = store, storeStateObservable = states)

    private fun results(query: String) =
        GifSearchViewModel.StoreState.SearchResults(listOf(gif), emptyList(), emptyList(), query)

    @Test
    fun `results for an earlier keystroke keep the shimmer up`() {
        val viewModel = viewModel()
        viewModel.setSearchText("dog")
        states.onNext(results("d"))
        assertEquals(GifSearchViewModel.ViewState.LoadingSearchResults, viewModel.viewState)

        states.onNext(results("dog"))
        assertTrue(viewModel.viewState is GifSearchViewModel.ViewState.Loaded)
    }

    @Test
    fun `switching provider with a query shows the shimmer`() {
        val viewModel = viewModel()
        viewModel.setSearchText("dog")
        states.onNext(results("dog"))

        viewModel.setProvider(GifProvider.GIPHY)
        assertEquals(GifSearchViewModel.ViewState.LoadingSearchResults, viewModel.viewState)
        assertEquals(GifProvider.GIPHY, store.provider)
    }
}
