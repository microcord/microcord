package com.discord.widgets.chat.input.gifpicker

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppViewFlipper
import com.discord.app.appViewModels
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.recycler.GridColumnSpaceItemDecoration
import com.discord.utilities.rx.ui
import com.discord.views.SearchInputView
import com.discord.widgets.chat.input.gifpicker.GifAdapterItem.GifItem

/**
 * Tenor search: a search field over a staggered result grid.
 *
 * The grid and the shimmer are the two children of a view flipper, so a query in flight replaces
 * the previous results rather than leaving stale GIFs under a spinner. Tapping a suggested term
 * writes it back into the search field, which re-enters the same pipeline as typing.
 */
class WidgetGifPickerSearch : AppFragment(R.layout.widget_gif_picker_search) {

    private val gifPickerViewModel: GifSearchViewModel by appViewModels { GifSearchViewModel() }

    private lateinit var gifRecycler: RecyclerView
    private lateinit var loadingView: GifLoadingView
    private lateinit var viewFlipper: AppViewFlipper
    private lateinit var searchInput: SearchInputView
    private lateinit var gifAdapter: GifAdapter

    private var onGifSelected: (() -> Unit)? = null

    fun setOnGifSelected(onSelected: () -> Unit) {
        onGifSelected = onSelected
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        gifRecycler = view.findViewById(R.id.gif_search_gif_recycler)
        loadingView = view.findViewById(R.id.gif_search_loading_view)
        viewFlipper = view.findViewById(R.id.gif_search_view_flipper)
        searchInput = view.findViewById(R.id.search_input)

        val columns = GifAdapter.calculateColumnCount(gifRecycler)
        setUpGifRecycler(columns)
        setUpSearchBar()
        loadingView.updateView(columns)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        searchInput.getEditText().requestFocus()
        gifPickerViewModel.observeViewState()
            .distinctUntilChanged()
            .ui(this)
            .subscribe({ handleViewState(it) }, { /* leave the last rendered results in place */ })
    }

    private fun handleViewState(viewState: GifSearchViewModel.ViewState) = when (viewState) {
        is GifSearchViewModel.ViewState.Loaded -> {
            gifAdapter.setItems(viewState.adapterItems)
            viewFlipper.displayedChild = FLIPPER_LOADED
        }

        GifSearchViewModel.ViewState.LoadingSearchResults -> {
            gifAdapter.clearItems()
            viewFlipper.displayedChild = FLIPPER_LOADING
        }
    }

    private fun setUpGifRecycler(categoryColumnsCount: Int) {
        gifRecycler.layoutManager =
            StaggeredGridLayoutManager(categoryColumnsCount, StaggeredGridLayoutManager.VERTICAL)
        gifRecycler.itemAnimator = null
        val columnSpacePx = dpToPixels(COLUMN_SPACE_DP)
        gifAdapter = GifAdapter(
            appComponent = this,
            onSelectGif = ::selectGif,
            columnWidthPx = GifAdapter.calculateColumnWidth(gifRecycler, categoryColumnsCount, columnSpacePx),
            onSelectSuggestedTerm = { term -> searchInput.setText(term) },
        )
        gifRecycler.adapter = gifAdapter
        gifRecycler.addItemDecoration(GridColumnSpaceItemDecoration(columnSpacePx, categoryColumnsCount))
    }

    private fun selectGif(gifItem: GifItem) {
        gifPickerViewModel.selectGif(gifItem)
        onGifSelected?.invoke()
    }

    private fun setUpSearchBar() {
        searchInput.bindTextWatcher(this) { searchQuery -> gifPickerViewModel.setSearchText(searchQuery) }
    }

    fun clearSearchBar() = gifPickerViewModel.setSearchText("")


    private companion object {
        const val FLIPPER_LOADED = 0
        const val FLIPPER_LOADING = 1
        const val COLUMN_SPACE_DP = 8
    }
}
