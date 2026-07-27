package com.discord.widgets.chat.input.sticker

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppViewFlipper
import com.discord.app.appActivityViewModels
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.locale.LocaleManager
import com.discord.utilities.recycler.SelfHealingLinearLayoutManager
import com.discord.utilities.recycler.SpeedOnScrollListener
import com.discord.utilities.rx.ui
import com.discord.views.LoadingButton
import com.discord.views.SearchInputView
import com.discord.views.sticker.StickerView
import com.discord.widgets.stickers.StickerPremiumUpsellDialog
import com.discord.widgets.stickers.UnsendableStickerPremiumUpsellDialog
import com.discord.widgets.stickers.WidgetStickerPackDetailsDialog
import kotlin.math.max
import kotlin.math.min
import rx.subjects.BehaviorSubject

/**
 * The sticker picker: a grid of every sticker the user can send, a tab rail indexing into it, and
 * (in sheet mode) a search bar.
 *
 * It is used twice — embedded in the chat input's expression tray ([StickerPickerMode.INLINE]) and
 * as its own bottom sheet ([WidgetStickerPickerSheet]). The two get *separate* activity-scoped view
 * models so a search typed in the sheet does not rewrite the tray behind it.
 */
class WidgetStickerPicker : AppFragment(R.layout.widget_sticker_picker) {

    private val viewModelForInline: StickerPickerInlineViewModel by appActivityViewModels {
        StickerPickerInlineViewModel(
            LocaleManager().getPrimaryLocale(requireContext()),
            initialStickerPackId,
        )
    }

    private val viewModelForSheet: StickerPickerSheetViewModel by appActivityViewModels {
        StickerPickerSheetViewModel(
            LocaleManager().getPrimaryLocale(requireContext()),
            initialStickerPackId,
        )
    }

    private val viewModel: StickerPickerViewModel
        get() = if (mode == StickerPickerMode.INLINE) viewModelForInline else viewModelForSheet

    /** `AppFragment` has no `argumentsOrDefault`; the sheet base class does. */
    private val args: Bundle get() = arguments ?: Bundle.EMPTY

    private val mode: StickerPickerMode
        get() = args.getSerializable(ARG_MODE) as? StickerPickerMode
            ?: StickerPickerMode.INLINE

    private val initialStickerPackId: Long
        get() = args.getLong(EXTRA_STICKER_PACK_ID, NO_PACK)

    /** Set by the host tray so it can hide its own search field while an empty state is showing. */
    var showSearchBar: ((Boolean) -> Unit)? = null

    /** Set by the host tray so an empty state is not left scrolled halfway down. */
    var scrollExpressionPickerToTop: () -> Unit = {}

    var stickerPickerListener: StickerPickerListener? = null

    /**
     * `false` while the grid is being flung. Sticker tiles watch it and hold off downloading and
     * decoding until it goes `true`, which is what keeps a fast scroll from stuttering.
     */
    private val recyclerScrollingWithinThresholdSubject = BehaviorSubject.create(true)

    private lateinit var container: View
    private lateinit var appBar: View
    private lateinit var searchInput: SearchInputView
    private lateinit var flipper: AppViewFlipper
    private lateinit var stickerRecycler: RecyclerView
    private lateinit var categoryRecycler: RecyclerView
    private lateinit var bottomBar: View
    private lateinit var bottomBarDivider: View
    private lateinit var emptyImage: ImageView
    private lateinit var emptyTitle: TextView
    private lateinit var emptySubtitle: TextView
    private lateinit var emptyLink: TextView
    private lateinit var emptyStateStickers: List<StickerView>
    private lateinit var emptySubscribe: LoadingButton

    private lateinit var stickerAdapter: WidgetStickerAdapter
    private lateinit var categoryAdapter: StickerCategoryAdapter
    private lateinit var categoryLayoutManager: LinearLayoutManager

    /**
     * The pack a tab tap is scrolling towards. While it is set, the scroll listener ignores every
     * category it passes over, so the selection does not flicker through the intervening tabs.
     */
    private var autoscrollToPackId: Long? = null
    private var restoredSearchQueryFromViewModel = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        container = view.findViewById(R.id.sticker_picker_container)
        appBar = view.findViewById(R.id.sticker_app_bar)
        searchInput = view.findViewById(R.id.sticker_search_input)
        flipper = view.findViewById(R.id.chat_input_sticker_picker_view_flipper)
        stickerRecycler = view.findViewById(R.id.chat_input_sticker_picker_recycler)
        categoryRecycler = view.findViewById(R.id.sticker_picker_category_recycler)
        bottomBar = view.findViewById(R.id.sticker_picker_bottom_bar)
        bottomBarDivider = view.findViewById(R.id.sticker_picker_bottom_bar_divider)
        emptyImage = view.findViewById(R.id.chat_input_sticker_picker_empty_image)
        emptyTitle = view.findViewById(R.id.chat_input_sticker_picker_empty_title)
        emptySubtitle = view.findViewById(R.id.chat_input_sticker_picker_empty_subtitle)
        emptyLink = view.findViewById(R.id.chat_input_sticker_picker_empty_link)
        emptyStateStickers = listOf(
            view.findViewById(R.id.chat_input_sticker_picker_empty_sticker_1),
            view.findViewById(R.id.chat_input_sticker_picker_empty_sticker_2),
            view.findViewById(R.id.chat_input_sticker_picker_empty_sticker_3),
            view.findViewById(R.id.chat_input_sticker_picker_empty_sticker_4),
        )
        emptySubscribe = view.findViewById(R.id.chat_input_sticker_picker_empty_subscribe)

        container.setPadding(
            container.paddingLeft,
            container.paddingTop,
            container.paddingRight,
            additionalBottomPaddingPx(),
        )
        if (mode == StickerPickerMode.INLINE) setWindowInsetsListeners()

        initializeSearchBar()
        setUpStickerRecycler()
        setUpCategoryRecycler()

        // Selecting a tab up front also stops the view model auto-scrolling to the pack it was
        // opened on every time the store emits.
        viewModel.setSelectedCategoryId(
            args.getLong(EXTRA_STICKER_PACK_ID, StickerPickerViewModel.RECENT_SELECTED_ID),
        )

        emptySubscribe.setIsLoading(false)
        emptySubscribe.setOnClickListener {
            StickerPremiumUpsellDialog.show(parentFragmentManager)
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
        viewModel.observeEvents().ui(this).subscribe(::handleEvent)
    }

    // region setup

    private fun initializeSearchBar() {
        // The inline tray already has the composer's own search field above it.
        val showSearch = mode != StickerPickerMode.INLINE
        appBar.visibility = if (showSearch) View.VISIBLE else View.GONE
        searchInput.visibility = if (showSearch) View.VISIBLE else View.GONE
        searchInput.bindTextWatcher(this) { query -> viewModel.setSearchText(query) }
        searchInput.onClearClicked = {
            if (mode == StickerPickerMode.INLINE) launchBottomSheet() else searchInput.setText("")
        }
        if (mode == StickerPickerMode.BOTTOM_SHEET) {
            searchInput.getEditText().requestFocus()
            showKeyboard(searchInput.getEditText())
        }
        args.getString(EXTRA_SEARCH_TEXT)?.let { text ->
            searchInput.setText(text)
            viewModel.setSearchText(text)
            restoredSearchQueryFromViewModel = true
        }
    }

    private fun setUpStickerRecycler() {
        stickerRecycler.itemAnimator = null
        stickerRecycler.setHasFixedSize(true)
        stickerAdapter = WidgetStickerAdapter(
            recycler = stickerRecycler,
            onStickerItemSelected = ::onStickerItemSelected,
            onStickerHeaderItemClicked = ::onStickerHeaderItemClicked,
            recyclerScrollingWithinThresholdSubject = recyclerScrollingWithinThresholdSubject,
            appComponent = this,
        )
        stickerRecycler.setRecyclerListener { holder ->
            (holder as? StickerViewHolder)?.cancelLoading()
        }
        stickerRecycler.addOnScrollListener(
            SpeedOnScrollListener(
                thresholdCallback = { withinThreshold ->
                    recyclerScrollingWithinThresholdSubject.onNext(withinThreshold)
                },
            ),
        )
    }

    private fun setUpCategoryRecycler() {
        categoryRecycler.itemAnimator = null
        categoryAdapter = StickerCategoryAdapter(
            onGuildClicked = ::onGuildClicked,
            onPackClicked = ::onPackClicked,
            onRecentClicked = ::onRecentClicked,
            onSelectedItemAdapterPositionUpdated = ::onSelectedCategoryAdapterPositionUpdated,
            appComponent = this,
        )
        categoryAdapter.setHasStableIds(true)
        categoryRecycler.adapter = categoryAdapter
        categoryLayoutManager = SelfHealingLinearLayoutManager(
            categoryRecycler,
            categoryAdapter,
            RecyclerView.HORIZONTAL,
            false,
        )
        categoryRecycler.layoutManager = categoryLayoutManager
    }

    // endregion

    // region rendering

    private fun configureUI(viewState: StickerPickerViewModel.ViewState) {
        // The view model is activity scoped, so a freshly created view has to pick the query back up.
        if (!restoredSearchQueryFromViewModel) {
            restoredSearchQueryFromViewModel = true
            searchInput.setText(viewState.searchQuery)
        }
        when (viewState) {
            is StickerPickerViewModel.ViewState.EmptyNonPremium -> {
                flipper.displayedChild = FLIPPER_EMPTY_STATE_PHASE_1
                showSearchBar?.invoke(false)
                scrollExpressionPickerToTop()
                if (viewState.emptyStateStickers.size >= emptyStateStickers.size) {
                    emptyStateStickers.forEachIndexed { index, stickerView ->
                        stickerView.setSticker(viewState.emptyStateStickers[index])
                    }
                }
                showCategoryBottomBar(false)
            }

            is StickerPickerViewModel.ViewState.EmptySearchResults -> {
                flipper.displayedChild = FLIPPER_EMPTY_STATE
                showSearchBar?.invoke(true)
                scrollExpressionPickerToTop()
                emptyImage.setImageResource(R.drawable.img_stickers_search_empty_90dp)
                emptyTitle.visibility = View.GONE
                emptySubtitle.text = getString(R.string.no_sticker_search_results)
                emptyLink.visibility = View.GONE
                showCategoryBottomBar(true)
                categoryAdapter.setItems(viewState.categoryItems)
            }

            is StickerPickerViewModel.ViewState.Stickers -> {
                flipper.displayedChild = FLIPPER_RESULTS
                showSearchBar?.invoke(true)
                stickerAdapter.setData(viewState.stickerItems)
                stickerAdapter.onScrollPositionListener = { position ->
                    handleNewStickerRecyclerScrollPosition(position, viewState.categoryItems)
                }
                showCategoryBottomBar(true)
                categoryAdapter.setItems(viewState.categoryItems)
                if (mode == StickerPickerMode.BOTTOM_SHEET) {
                    // Leave room for the sheet's search bar, which floats over the top of the grid.
                    (stickerRecycler.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                        params.topMargin = dpToPixels(SHEET_RECYCLER_TOP_MARGIN_DP)
                        stickerRecycler.layoutParams = params
                    }
                }
            }
        }
    }

    private fun handleEvent(event: StickerPickerViewModel.Event) = when (event) {
        is StickerPickerViewModel.Event.ScrollToStickerItemPosition ->
            stickerAdapter.scrollToPosition(event.position)
        StickerPickerViewModel.Event.ShowStickerPremiumUpsell ->
            UnsendableStickerPremiumUpsellDialog.show(parentFragmentManager)
        StickerPickerViewModel.Event.SlowMode ->
            Toast.makeText(requireContext(), R.string.channel_slowmode_desc_short, Toast.LENGTH_SHORT)
                .show()
    }

    private fun showCategoryBottomBar(show: Boolean) {
        bottomBar.visibility = if (show) View.VISIBLE else View.GONE
        bottomBarDivider.visibility = if (show) View.VISIBLE else View.GONE
        (flipper.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.bottomMargin = if (show) dpToPixels(CATEGORY_BAR_HEIGHT_DP) else 0
            flipper.layoutParams = params
        }
    }

    // endregion

    // region interaction

    private fun onStickerItemSelected(stickerItem: StickerItem) {
        if (viewModel.onStickerSelected(stickerItem.sticker)) {
            stickerPickerListener?.onStickerPicked(stickerItem.sticker)
        }
    }

    private fun onStickerHeaderItemClicked(item: StoreHeaderItem) =
        WidgetStickerPackDetailsDialog.show(childFragmentManager, item.pack.id)

    private fun onRecentClicked() {
        selectCategoryById(StickerPickerViewModel.RECENT_SELECTED_ID)
        stickerAdapter.scrollToPosition(0)
    }

    private fun onPackClicked(item: StickerCategoryItem.PackItem) {
        autoscrollToPackId = item.pack.id
        selectCategoryById(item.pack.id)
        stickerAdapter.scrollToPosition(item.categoryRange.first)
    }

    private fun onGuildClicked(item: StickerCategoryItem.GuildItem) {
        autoscrollToPackId = item.guild.id
        selectCategoryById(item.guild.id)
        stickerAdapter.scrollToPosition(item.categoryRange.first)
    }

    /** Keeps the selected tab on screen when the grid scrolls the selection off the rail. */
    private fun onSelectedCategoryAdapterPositionUpdated(selectedCategoryPosition: Int) {
        val first = categoryLayoutManager.findFirstCompletelyVisibleItemPosition()
        val last = categoryLayoutManager.findLastCompletelyVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return
        if (selectedCategoryPosition in first..last) return
        val span = last - first
        val target = if (selectedCategoryPosition < first) {
            selectedCategoryPosition - span
        } else {
            selectedCategoryPosition + span
        }
        categoryRecycler.scrollToPosition(
            min(max(target, 0), (categoryAdapter.itemCount - 1).coerceAtLeast(0)),
        )
    }

    private fun handleNewStickerRecyclerScrollPosition(
        position: Int,
        categoryItems: List<StickerCategoryItem>,
    ) {
        for (item in categoryItems) {
            val (start, end) = item.categoryRange
            if (position !in start until end) continue
            if (autoscrollToPackId != null && autoscrollToPackId != item.categoryId) continue
            autoscrollToPackId = null
            if (!item.isSelected) selectCategoryById(item.categoryId)
        }
    }

    private fun launchBottomSheet() {
        WidgetStickerPickerSheet.show(parentFragmentManager, stickerPickerListener)
    }

    // endregion

    // region host API

    fun clearSearchInput() {
        viewModel.setSearchText("")
        if (this::searchInput.isInitialized) searchInput.setText("")
    }

    fun selectCategoryById(categoryId: Long) = viewModel.setSelectedCategoryId(categoryId)

    fun scrollToPack(packId: Long?) = viewModel.scrollToPackId(packId)

    fun scrollToTop() {
        if (!this::stickerRecycler.isInitialized) return
        stickerRecycler.doOnLayout { stickerRecycler.scrollToPosition(0) }
    }

    /** Opens on [packId] with [searchText] pre-filled, once the grid has a size to scroll within. */
    fun setupForInlineSearchAndScroll(searchText: String = "", packId: Long) {
        stickerRecycler.doOnLayout {
            viewModel.setSearchText(searchText)
            viewModel.setSelectedCategoryId(packId)
            scrollToPack(packId)
        }
    }

    // endregion

    /** API 29+ draws a gesture bar over the bottom of the tray. */
    private fun additionalBottomPaddingPx(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) dpToPixels(8) else 0

    private fun setWindowInsetsListeners() {
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                additionalBottomPaddingPx() + insets.systemWindowInsetBottom,
            )
            @Suppress("DEPRECATION")
            insets.consumeSystemWindowInsets()
        }
    }

    /**
     * Belongs in `utilities/dimen/DimenUtils.dpToPixels`, which has not been reconstructed. Delete
     * this when it lands.
     */

    companion object {
        const val ARG_MODE = "MODE"
        const val EXTRA_STICKER_PACK_ID = "com.discord.intent.EXTRA_STICKER_PACK_ID"
        const val EXTRA_SEARCH_TEXT = "com.discord.intent.extra.EXTRA_TEXT"

        /** "no pack was requested"; anything that is not a real pack id would do. */
        const val NO_PACK = -1L

        private const val FLIPPER_RESULTS = 0
        private const val FLIPPER_EMPTY_STATE = 1
        private const val FLIPPER_EMPTY_STATE_PHASE_1 = 2

        private const val CATEGORY_BAR_HEIGHT_DP = 48
        private const val SHEET_RECYCLER_TOP_MARGIN_DP = 72

        fun newInstance(
            mode: StickerPickerMode,
            initialStickerPackId: Long? = null,
            searchText: String? = null,
        ): WidgetStickerPicker = WidgetStickerPicker().apply {
            arguments = Bundle().apply {
                putSerializable(ARG_MODE, mode)
                initialStickerPackId?.let { putLong(EXTRA_STICKER_PACK_ID, it) }
                searchText?.let { putString(EXTRA_SEARCH_TEXT, it) }
            }
        }
    }
}
