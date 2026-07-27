package com.discord.widgets.chat.input.gifpicker

import com.discord.app.AppViewModel
import com.discord.models.gifpicker.domain.ModelGifCategory
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable

/**
 * Drives the GIF picker's landing grid.
 *
 * There is only ever one thing to decide: whether the categories have arrived. Until they have,
 * [ViewState.isLoaded] is `false` and the fragment shows the shimmering placeholder grid, which is
 * also what a failed fetch leaves on screen — the store retries on the next subscribe, so there is
 * no terminal error state to render here.
 */
class GifPickerViewModel(
    storeStateObservable: Observable<StoreState> = observeStoreState(),
) : AppViewModel<GifPickerViewModel.ViewState>(ViewState(emptyList())) {

    data class StoreState(
        val gifCategories: List<ModelGifCategory>,
        val trendingGifCategoryPreviewUrl: String,
    )

    data class ViewState(val gifCategoryItems: List<GifCategoryItem>) {
        val isLoaded: Boolean = gifCategoryItems.isNotEmpty()
    }

    init {
        storeStateObservable
            .computationLatest()
            .ui(this)
            .subscribe({ handleStoreState(it) }, { /* keep the loading grid; the store retries */ })
    }

    private fun handleStoreState(storeState: StoreState) {
        updateViewState(ViewState(buildCategoryItems(storeState)))
    }

    companion object {
        /** The synthetic "Trending" tile leads, and only when its preview image has arrived. */
        fun buildCategoryItems(storeState: StoreState): List<GifCategoryItem> = buildList {
            if (storeState.trendingGifCategoryPreviewUrl.isNotEmpty()) {
                add(GifCategoryItem.Trending(storeState.trendingGifCategoryPreviewUrl))
            }
            addAll(storeState.gifCategories.map(GifCategoryItem::Standard))
        }

        fun observeStoreState(
            storeGifPicker: GifPickerStore = GifPickerStores.gifPicker,
        ): Observable<StoreState> = Observable.combineLatest(
            storeGifPicker.observeGifCategories(),
            storeGifPicker.observeTrendingGifCategoryPreviewUrl(),
        ) { categories, trendingPreviewUrl -> StoreState(categories, trendingPreviewUrl) }
    }
}
