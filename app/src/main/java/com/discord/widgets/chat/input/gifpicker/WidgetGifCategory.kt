package com.discord.widgets.chat.input.gifpicker

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appActivityViewModels
import com.discord.app.appViewModels
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.recycler.GridColumnSpaceItemDecoration
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.input.expression.ExpressionTrayViewModel
import com.discord.widgets.chat.input.gifpicker.GifAdapterItem.GifItem

/**
 * The detail page behind one category tile: a staggered grid of that category's GIFs.
 *
 * Back — either the system gesture or the title row's chevron — is routed to the shared
 * [ExpressionTrayViewModel] rather than popping a fragment, because the tray owns which page is
 * showing and the detail page is one of its states, not a back-stack entry.
 */
class WidgetGifCategory : AppFragment(R.layout.widget_gif_category) {

    private val expressionTrayViewModel: ExpressionTrayViewModel by appActivityViewModels {
        ExpressionTrayViewModel()
    }

    private val gifCategoryViewModel: GifCategoryViewModel by appViewModels {
        GifCategoryViewModel(gifCategory)
    }

    private lateinit var gifRecycler: RecyclerView
    private lateinit var loadingView: GifLoadingView
    private lateinit var title: TextView
    private lateinit var gifAdapter: GifAdapter

    private var onGifSelected: (() -> Unit)? = null

    private val gifCategory: GifCategoryItem
        get() = requireArguments().getSerializable(ARG_GIF_CATEGORY_ITEM) as GifCategoryItem

    fun setOnGifSelected(onSelected: () -> Unit) {
        onGifSelected = onSelected
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        gifRecycler = view.findViewById(R.id.gif_category_gif_recycler)
        loadingView = view.findViewById(R.id.gif_category_loading_view)
        title = view.findViewById(R.id.gif_category_title)

        val columns = GifAdapter.calculateColumnCount(gifRecycler)
        setUpTitle()
        setUpBackBehavior()
        setUpGifRecycler(columns)
        loadingView.updateView(columns)
        setWindowInsetsListeners()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        gifCategoryViewModel.observeViewState()
            .ui(this)
            .subscribe({ handleViewState(it) }, { /* keep the loading grid on screen */ })
    }

    private fun handleViewState(viewState: GifCategoryViewModel.ViewState) {
        gifRecycler.visibility = View.VISIBLE
        loadingView.visibility = View.GONE
        gifAdapter.setItems(viewState.gifItems)
    }

    private fun setUpTitle() {
        title.text = when (val category = gifCategory) {
            is GifCategoryItem.Standard -> category.gifCategory.categoryName
            is GifCategoryItem.Favorite -> getString(R.string.category_favorite)
            is GifCategoryItem.Frequent -> getString(R.string.frequently_used)
            is GifCategoryItem.Trending -> getString(R.string.gif_picker_result_type_trending_gifs)
        }
    }

    private fun setUpBackBehavior() {
        setOnBackPressed(handler = {
            handleBack()
            true
        }, priority = 0)
        title.setOnClickListener { handleBack() }
    }

    private fun handleBack() = expressionTrayViewModel.clickBack()

    private fun setUpGifRecycler(categoryColumnsCount: Int) {
        gifRecycler.layoutManager =
            StaggeredGridLayoutManager(categoryColumnsCount, StaggeredGridLayoutManager.VERTICAL)
        gifRecycler.itemAnimator = null
        val columnSpacePx = dpToPixels(COLUMN_SPACE_DP)
        gifAdapter = GifAdapter(
            appComponent = this,
            onSelectGif = ::selectGif,
            onToggleFavorite = gifCategoryViewModel::toggleFavorite,
            columnWidthPx = GifAdapter.calculateColumnWidth(gifRecycler, categoryColumnsCount, columnSpacePx),
        )
        gifRecycler.adapter = gifAdapter
        gifRecycler.addItemDecoration(GridColumnSpaceItemDecoration(columnSpacePx, categoryColumnsCount))
    }

    private fun selectGif(gifItem: GifItem) {
        gifCategoryViewModel.selectGif(gifItem)
        onGifSelected?.invoke()
    }

    private fun setWindowInsetsListeners() {
        ViewCompat.setOnApplyWindowInsetsListener(gifRecycler) { view, insets ->
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, insets.systemWindowInsetBottom)
            insets.consumeSystemWindowInsets()
        }
        ViewCompat.setOnApplyWindowInsetsListener(requireView()) { _, insets ->
            ViewCompat.dispatchApplyWindowInsets(gifRecycler, insets)
            insets.consumeSystemWindowInsets()
        }
    }


    companion object {
        const val ARG_GIF_CATEGORY_ITEM = "GIF_CATEGORY_ITEM"

        private const val COLUMN_SPACE_DP = 8

        fun newInstance(gifCategoryItem: GifCategoryItem): WidgetGifCategory =
            WidgetGifCategory().apply {
                arguments = Bundle().apply { putSerializable(ARG_GIF_CATEGORY_ITEM, gifCategoryItem) }
            }
    }
}
