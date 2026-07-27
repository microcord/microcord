package com.discord.widgets.chat.input.gifpicker

import android.view.View
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.recycler.GridColumnSpaceItemDecoration
import com.discord.utilities.rx.ui

/**
 * The GIF picker's landing page: a two-column grid of category tiles.
 *
 * Tapping a tile does not navigate from here — the host tray owns the detail page, so the fragment
 * only reports the selection upwards through [setOnSelectGifCategory].
 */
class WidgetGifPicker : AppFragment(R.layout.widget_gif_picker) {

    private val viewModel: GifPickerViewModel by appViewModels { GifPickerViewModel() }

    private lateinit var categoryRecycler: RecyclerView
    private lateinit var loadingView: GifLoadingView
    private lateinit var categoryAdapter: GifCategoryAdapter

    private var onSelectGifCategory: ((GifCategoryItem) -> Unit)? = null

    fun setOnSelectGifCategory(onGifCategorySelected: (GifCategoryItem) -> Unit) {
        onSelectGifCategory = onGifCategorySelected
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        categoryRecycler = view.findViewById(R.id.gif_picker_category_recycler)
        loadingView = view.findViewById(R.id.gif_picker_loading_view)
        setUpCategoryRecycler()
        setWindowInsetsListeners()
        loadingView.updateView(CATEGORY_COLUMNS_COUNT)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .ui(this)
            .subscribe({ handleViewState(it) }, { /* keep whatever is already on screen */ })
    }

    private fun handleViewState(viewState: GifPickerViewModel.ViewState) {
        categoryRecycler.visibility = if (viewState.isLoaded) View.VISIBLE else View.GONE
        loadingView.visibility = if (viewState.isLoaded) View.GONE else View.VISIBLE
        categoryAdapter.setItems(viewState.gifCategoryItems)
    }

    private fun setUpCategoryRecycler() {
        categoryRecycler.layoutManager = GridLayoutManager(context, CATEGORY_COLUMNS_COUNT)
        // Tiles are fixed-size and never reorder, so the default change animation only costs frames.
        categoryRecycler.itemAnimator = null
        // Reads the field per click rather than capturing it, so a host that installs its callback
        // after the view is bound still receives selections.
        categoryAdapter = GifCategoryAdapter(this) { item -> onSelectGifCategory?.invoke(item) }
        categoryRecycler.adapter = categoryAdapter
        categoryRecycler.addItemDecoration(
            GridColumnSpaceItemDecoration(dpToPixels(COLUMN_SPACE_DP), CATEGORY_COLUMNS_COUNT),
        )
    }

    /**
     * The grid and the loading grid are siblings in a `FrameLayout`, so neither is reached by the
     * insets dispatched to the frame; the frame forwards them explicitly.
     */
    private fun setWindowInsetsListeners() {
        listOf<View>(categoryRecycler, loadingView).forEach { target ->
            ViewCompat.setOnApplyWindowInsetsListener(target) { view, insets ->
                view.setPadding(
                    view.paddingLeft,
                    view.paddingTop,
                    view.paddingRight,
                    insets.systemWindowInsetBottom,
                )
                insets.consumeSystemWindowInsets()
            }
        }
        val container = requireView()
        ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            ViewCompat.dispatchApplyWindowInsets(categoryRecycler, insets)
            ViewCompat.dispatchApplyWindowInsets(loadingView, insets)
            insets.consumeSystemWindowInsets()
        }
    }

    fun scrollToTop() {
        if (::categoryAdapter.isInitialized && categoryAdapter.itemCount > 0) {
            categoryRecycler.scrollToPosition(0)
        }
    }


    private companion object {
        const val CATEGORY_COLUMNS_COUNT = 2
        const val COLUMN_SPACE_DP = 8
    }
}
