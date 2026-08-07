package com.discord.widgets.chat.input.expression

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appActivityViewModels
import com.discord.models.domain.emoji.Emoji
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.analytics.ChatInputComponentTypes
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.setForwardingWindowInsetsListener
import com.discord.views.segmentedcontrol.SegmentedControlContainer
import com.discord.widgets.chat.input.AppFlexInputViewModel
import com.discord.widgets.chat.input.OnBackspacePressedListener
import com.discord.widgets.chat.input.emoji.EmojiPickerContextType
import com.discord.widgets.chat.input.emoji.EmojiPickerListener
import com.discord.widgets.chat.input.emoji.WidgetEmojiPicker
import com.discord.widgets.chat.input.gifpicker.GifCategoryItem
import com.discord.widgets.chat.input.gifpicker.WidgetGifCategory
import com.discord.widgets.chat.input.gifpicker.WidgetGifPicker
import com.discord.widgets.chat.input.gifpicker.WidgetGifPickerSheet
import com.discord.widgets.chat.input.sticker.StickerPickerListener
import com.discord.widgets.chat.input.sticker.StickerPickerMode
import com.discord.widgets.chat.input.sticker.WidgetStickerPicker
import com.discord.widgets.chat.input.sticker.WidgetStickerPickerSheet
import com.google.android.material.appbar.AppBarLayout
import rx.subjects.BehaviorSubject

/**
 * The chat input's expression tray: emoji, GIF and sticker pickers behind one segmented control.
 *
 * The tray owns *which* picker is showing; each picker owns its own content. Two consequences shape
 * this class:
 *
 *  - **Pickers are created lazily and then kept.** A tab is only instantiated the first time it is
 *    selected (`*Initialized` flags), because the sticker and emoji grids are expensive and most
 *    sessions never leave the emoji tab. Once created they are never removed, so switching tabs is
 *    a visibility change and scroll position survives.
 *  - **The detail page is a tray state, not a back-stack entry.** Drilling into a GIF category
 *    replaces the whole landing page; back is handled by [ExpressionTrayViewModel.clickBack].
 *
 * It hosts the emoji picker directly, so it implements [WidgetEmojiPicker.Host] and forwards to
 * whatever the composer installed through [setEmojiPickerListener] / [setOnBackspacePressedListener].
 */
class WidgetExpressionTray : AppFragment(R.layout.widget_expression_tray), WidgetEmojiPicker.Host {

    private val expressionTrayViewModel: ExpressionTrayViewModel by appActivityViewModels {
        ExpressionTrayViewModel()
    }

    private val flexInputViewModel: AppFlexInputViewModel by appActivityViewModels {
        AppFlexInputViewModel()
    }

    private lateinit var landingPage: View
    private lateinit var detailPage: View
    private lateinit var contentContainer: ViewGroup
    private lateinit var searchBar: View
    private lateinit var searchButton: TextView
    private lateinit var segmentedControl: SegmentedControlContainer
    private lateinit var gifCard: View
    private lateinit var stickerCard: View
    private lateinit var appBarLayout: AppBarLayout

    /** Insertion order is the tab order; the segmented control indexes into it. */
    private lateinit var expressionTabToContentViews: Map<ExpressionTrayTab, View>

    private var emojiPickerFragment: WidgetEmojiPicker? = null
    private var gifPickerFragment: WidgetGifPicker? = null
    private var stickerPickerFragment: WidgetStickerPicker? = null

    private var emojiPickerListener: EmojiPickerListener? = null
    private var stickerPickerListener: StickerPickerListener? = null
    private var onBackspacePressedListener: OnBackspacePressedListener? = null
    private var onEmojiSearchOpenedListener: (() -> Unit)? = null

    /** `true` while the tray's app bar is fully expanded; pickers read it to gate over-scroll. */
    private val isAtInitialScrollPositionSubject = BehaviorSubject.create(true)

    private var wasActive = false

    fun setEmojiPickerListener(emojiPickerListener: EmojiPickerListener) {
        this.emojiPickerListener = emojiPickerListener
    }

    fun setStickerPickerListener(stickerPickerListener: StickerPickerListener) {
        this.stickerPickerListener = stickerPickerListener
        stickerPickerFragment?.stickerPickerListener = stickerPickerListener
    }

    fun setOnBackspacePressedListener(onBackspacePressedListener: OnBackspacePressedListener?) {
        this.onBackspacePressedListener = onBackspacePressedListener
    }

    fun setOnEmojiSearchOpenedListener(callback: () -> Unit) {
        onEmojiSearchOpenedListener = callback
    }

    // region emoji picker host

    override fun onEmojiPicked(emoji: Emoji) {
        emojiPickerListener?.onEmojiPicked(emoji)
    }

    override fun onEmojiBackspace() {
        onBackspacePressedListener?.onBackspacePressed()
    }

    // endregion

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        landingPage = view.findViewById(R.id.expression_tray_landing_page)
        detailPage = view.findViewById(R.id.expression_tray_detail_page)
        contentContainer = view.findViewById(R.id.expression_tray_content_container)
        searchBar = view.findViewById(R.id.expression_tray_search_bar)
        searchButton = view.findViewById(R.id.expression_tray_search_button)
        segmentedControl = view.findViewById(R.id.expression_tray_segmented_control)
        gifCard = view.findViewById(R.id.expression_tray_gif_card)
        stickerCard = view.findViewById(R.id.expression_tray_sticker_card)
        appBarLayout = view.findViewById(R.id.expression_tray_toolbar_layout)

        initializeExpressionTabToViewsMap(view)
        setUpTabs()
        searchBar.setOnClickListener {
            expressionTrayViewModel.clickSearch(searchButton.text.toString())
        }
        setWindowInsetsListeners(view)
        appBarLayout.addOnOffsetChangedListener(
            AppBarLayout.OnOffsetChangedListener { _, verticalOffset ->
                isAtInitialScrollPositionSubject.onNext(verticalOffset == 0)
            },
        )
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        expressionTrayViewModel.observeViewState()
            .ui(this)
            .appSubscribe(WidgetExpressionTray::class.java, onNext = ::handleViewState)
        expressionTrayViewModel.observeEvents()
            .ui(this)
            .appSubscribe(WidgetExpressionTray::class.java, onNext = ::handleEvent)
        (parentFragmentManager.findFragmentByTag(WidgetEmojiPickerChatSheet::class.java.simpleName)
            as? WidgetEmojiPickerChatSheet)?.let(::bindEmojiPickerSheet)
        flexInputViewModel.viewState?.let { isShown(it.expressionTrayShown) }
    }

    private fun initializeExpressionTabToViewsMap(view: View) {
        expressionTabToContentViews = linkedMapOf(
            ExpressionTrayTab.EMOJI to view.findViewById(R.id.expression_tray_emoji_picker_content),
            ExpressionTrayTab.GIF to view.findViewById(R.id.expression_tray_gif_picker_content),
            ExpressionTrayTab.STICKER to view.findViewById(R.id.expression_tray_sticker_picker_content),
        )
    }

    private fun setUpTabs() {
        segmentedControl.setup(tabIndexOf(ExpressionTrayTab.EMOJI))
        segmentedControl.setOnSegmentSelectedChangeListener { index ->
            val tab = ExpressionTrayTab.values()[index]
            // The tray's search field is per-tab; carrying a sticker query onto the emoji tab would
            // show a query that no longer filters anything.
            searchButton.text = ""
            stickerPickerFragment?.takeIf { it.isVisible }?.clearSearchInput()
            expressionTrayViewModel.selectTab(tab)
            AnalyticsTracker.expressionPickerTabClicked(analyticsTabName(tab), false)
        }
    }

    private fun handleViewState(viewState: ExpressionTrayViewModel.ViewState) {
        configureLandingPage(viewState)
        configureDetailPage(viewState)

        landingPage.visibility = if (viewState.showLandingPage) View.VISIBLE else View.GONE
        detailPage.visibility = if (viewState.showLandingPage) View.GONE else View.VISIBLE
        searchBar.visibility = if (viewState.showSearchBar) View.VISIBLE else View.GONE

        // Losing a tab while standing on it would leave an empty tray, so fall back to emoji first
        // and only then hide the segment.
        if (stickerCard.visibility == View.VISIBLE &&
            !viewState.showGifsAndStickers &&
            viewState.selectedExpressionTab == ExpressionTrayTab.STICKER
        ) {
            expressionTrayViewModel.selectTab(ExpressionTrayTab.EMOJI)
        }
        if (gifCard.visibility == View.VISIBLE &&
            !viewState.showGifsAndStickers &&
            viewState.selectedExpressionTab == ExpressionTrayTab.GIF
        ) {
            expressionTrayViewModel.selectTab(ExpressionTrayTab.EMOJI)
        }

        stickerCard.visibility = if (viewState.showGifsAndStickers) View.VISIBLE else View.GONE
        gifCard.visibility = if (viewState.showGifsAndStickers) View.VISIBLE else View.GONE
        // A one-segment control is not a control.
        segmentedControl.visibility = if (viewState.showGifsAndStickers) View.VISIBLE else View.GONE
    }

    private fun configureLandingPage(viewState: ExpressionTrayViewModel.ViewState) {
        val tab = viewState.selectedExpressionTab
        when (tab) {
            ExpressionTrayTab.EMOJI -> setUpEmojiPicker()
            ExpressionTrayTab.GIF -> setUpGifPicker()
            ExpressionTrayTab.STICKER -> setUpStickerPicker()
        }

        segmentedControl.setSelectedIndex(tabIndexOf(tab))
        expressionTabToContentViews.forEach { (contentTab, contentView) ->
            contentView.visibility = if (contentTab == tab) View.VISIBLE else View.GONE
        }

        val searchHint = getString(
            when (tab) {
                ExpressionTrayTab.EMOJI -> R.string.search_for_emoji
                ExpressionTrayTab.GIF -> R.string.search_tenor
                ExpressionTrayTab.STICKER -> R.string.search_for_stickers
            },
        )
        searchButton.hint = searchHint
        searchBar.contentDescription = searchHint
    }

    private fun configureDetailPage(viewState: ExpressionTrayViewModel.ViewState) {
        when (val page = viewState.expressionDetailPage) {
            is ExpressionDetailPage.GifCategoryPage -> {
                val fragment = WidgetGifCategory.newInstance(page.gifCategoryItem)
                fragment.setOnGifSelected(::onGifSelected)
                setUpTabFragment(R.id.expression_tray_detail_page, fragment, fragment.javaClass.simpleName)
            }

            null -> childFragmentManager.findFragmentById(R.id.expression_tray_detail_page)?.let {
                childFragmentManager.beginTransaction().remove(it).commit()
            }
        }
    }

    private fun handleEvent(event: ExpressionTrayViewModel.Event) {
        when (event) {
            ExpressionTrayViewModel.Event.HideExpressionTray -> flexInputViewModel.hideExpressionTray()

            ExpressionTrayViewModel.Event.ShowEmojiPickerSheet -> {
                onEmojiSearchOpenedListener?.invoke()
                AnalyticsTracker.chatInputComponentViewed(ChatInputComponentTypes.EMOJI_SEARCH)
                WidgetEmojiPickerChatSheet.show(parentFragmentManager, null)
                    .also(::bindEmojiPickerSheet)
            }

            ExpressionTrayViewModel.Event.ShowGifPickerSheet -> {
                AnalyticsTracker.chatInputComponentViewed(ChatInputComponentTypes.GIF_SEARCH)
                WidgetGifPickerSheet.show(
                    parentFragmentManager,
                    onGifSelected = ::onGifSelected,
                    onCancel = ::onGifSearchSheetCanceled,
                )
            }

            is ExpressionTrayViewModel.Event.ShowStickerPicker -> handleShowStickerPicker(event)
        }
    }

    private fun bindEmojiPickerSheet(sheet: WidgetEmojiPickerChatSheet) {
        sheet.bindCallbacks(
            listener = emojiPickerListener,
            onCancel = { if (isAdded) flexInputViewModel.showKeyboardAndHideExpressionTray() },
        )
    }

    private fun handleShowStickerPicker(event: ExpressionTrayViewModel.Event.ShowStickerPicker) {
        if (!event.inline) {
            AnalyticsTracker.chatInputComponentViewed(ChatInputComponentTypes.STICKER_SEARCH)
            WidgetStickerPickerSheet.show(
                fragmentManager = parentFragmentManager,
                stickerPickerListener = stickerPickerListener,
                initialStickerPackId = event.stickerPackId,
                searchText = event.searchText,
                onCancel = { flexInputViewModel.hideKeyboard() },
            )
            return
        }

        AnalyticsTracker.chatInputComponentViewed(ChatInputComponentTypes.STICKER)
        setUpStickerPicker()
        flexInputViewModel.showExpressionTray()
        searchButton.text = event.searchText
        hideKeyboard(view)
        stickerPickerFragment?.setupForInlineSearchAndScroll(
            searchText = event.searchText.orEmpty(),
            packId = event.stickerPackId ?: ExpressionTrayViewModel.NO_STICKER_PACK,
        )
    }

    // region lazy tab construction

    private fun setUpEmojiPicker() {
        if (emojiPickerFragment != null) return
        val fragment = WidgetEmojiPicker.create(EmojiPickerContextType.Chat)
        emojiPickerFragment = fragment
        setUpTabFragment(
            R.id.expression_tray_emoji_picker_content,
            fragment,
            fragment.javaClass.simpleName,
        )
    }

    private fun setUpGifPicker() {
        if (gifPickerFragment != null) return
        val fragment = WidgetGifPicker()
        fragment.setOnSelectGifCategory(expressionTrayViewModel::selectGifCategory)
        gifPickerFragment = fragment
        setUpTabFragment(R.id.expression_tray_gif_picker_content, fragment, fragment.javaClass.simpleName)
    }

    private fun setUpStickerPicker() {
        if (stickerPickerFragment != null) return
        val fragment = WidgetStickerPicker.newInstance(StickerPickerMode.INLINE)
        fragment.stickerPickerListener = stickerPickerListener
        fragment.showSearchBar = ::showStickersSearchBar
        fragment.scrollExpressionPickerToTop = {
            appBarLayout.setExpanded(true, false)
        }
        stickerPickerFragment = fragment
        setUpTabFragment(R.id.expression_tray_sticker_picker_content, fragment, fragment.javaClass.simpleName)
    }

    /** Silently skipped after `onSaveInstanceState`; the state that drove it is re-delivered. */
    private fun setUpTabFragment(fragmentId: Int, fragment: Fragment, fragmentName: String) {
        if (isStateSaved) return
        childFragmentManager.beginTransaction().replace(fragmentId, fragment, fragmentName).commit()
    }

    // endregion

    private fun showStickersSearchBar(show: Boolean) = expressionTrayViewModel.showStickersSearchBar(show)

    private fun onGifSelected() {
        if (isAdded) flexInputViewModel.showKeyboardAndHideExpressionTray()
    }

    private fun onGifSearchSheetCanceled() {
        if (isAdded) flexInputViewModel.showKeyboardAndHideExpressionTray()
    }

    private fun fetchDataForTrayOpen() = StoreStream.getStickers().fetchEnabledStickerDirectory()

    /**
     * Told by the composer whether the tray is the visible surface.
     *
     * Opening resets every instantiated picker to the top and refreshes the sticker directory;
     * closing unwinds any detail page and clears the search field, so the next open is not showing
     * the tail of the last session.
     */
    fun isShown(isActive: Boolean) {
        expressionTrayViewModel.handleIsActive(isActive)
        isExpressionTrayActiveSubject.onNext(isActive)

        if (isActive && !wasActive) {
            appBarLayout.setExpanded(true)
            // The emoji picker has no scrollToTop() yet — its grid keeps its position across opens.
            gifPickerFragment?.scrollToTop()
            stickerPickerFragment?.scrollToTop()
            fetchDataForTrayOpen()
            trackExpressionPickerOpened()
        } else if (!isActive && wasActive) {
            expressionTrayViewModel.clickBack()
        }

        if (!isActive) {
            searchButton.text = ""
            stickerPickerFragment?.takeIf { it.isVisible }?.clearSearchInput()
        }

        wasActive = isActive
    }

    private fun trackExpressionPickerOpened() {
        val tab = expressionTrayViewModel.viewState?.selectedExpressionTab ?: return
        AnalyticsTracker.expressionPickerOpened(analyticsTabName(tab), false)
    }

    private fun analyticsTabName(tab: ExpressionTrayTab): String = when (tab) {
        ExpressionTrayTab.EMOJI -> "emoji"
        ExpressionTrayTab.GIF -> "gif"
        ExpressionTrayTab.STICKER -> "sticker"
    }

    private fun tabIndexOf(tab: ExpressionTrayTab): Int =
        expressionTabToContentViews.keys.indexOf(tab)

    /**
     * The three picker containers are siblings in a `FrameLayout`, so none of them is reached by
     * the insets dispatched to the frame; each is given a forwarding listener and the frame fans
     * the insets out explicitly.
     */
    private fun setWindowInsetsListeners(root: View) {
        expressionTabToContentViews.values.forEach { (it as? ViewGroup)?.setForwardingWindowInsetsListener() }
        (detailPage as? ViewGroup)?.setForwardingWindowInsetsListener()
        (root as? ViewGroup)?.setForwardingWindowInsetsListener()

        ViewCompat.setOnApplyWindowInsetsListener(contentContainer) { _, insets ->
            expressionTabToContentViews.values.forEach { ViewCompat.dispatchApplyWindowInsets(it, insets) }
            insets.consumeSystemWindowInsets()
        }
        ViewCompat.setOnApplyWindowInsetsListener(landingPage) { _, insets ->
            ViewCompat.dispatchApplyWindowInsets(contentContainer, insets)
            insets.consumeSystemWindowInsets()
        }
    }

    companion object {
        /**
         * Whether *any* expression tray is currently the visible surface.
         *
         * Process-wide, as in the reference: the sticker picker's view model needs it to know
         * whether it is the selected tab, and it has no path to this fragment.
         */
        @JvmStatic
        val isExpressionTrayActiveSubject: BehaviorSubject<Boolean> = BehaviorSubject.create(false)
    }
}
