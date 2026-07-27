package com.discord.widgets.chat.input.expression

import com.discord.api.sticker.Sticker
import com.discord.app.AppViewModel
import com.discord.models.user.MeUser
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.analytics.ChatInputComponentTypes
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.input.gifpicker.GifCategoryItem
import com.discord.widgets.stickers.GuildStickerStore
import com.discord.widgets.stickers.StickerStores
import rx.Observable
import rx.subjects.PublishSubject

/**
 * Owns which expression-tray page is showing.
 *
 * Activity-scoped, because three sibling fragments read it: the tray itself, and the GIF category
 * detail page whose back button has to unwind the tray's own navigation rather than the fragment
 * back stack.
 *
 * Two inputs feed it. The store side supplies the persisted tab and whether GIFs/stickers are
 * available at all (they are not while a thread-creation draft is open, because there is no channel
 * to send into yet). The [ExpressionPickerEventBus] side supplies one-shot commands from outside
 * the tray. Everything the *view* can do — pick a tab, drill into a category, go back, open search —
 * arrives through the public methods.
 */
class ExpressionTrayViewModel(
    private val storeExpressionPickerNavigation: ExpressionPickerNavigationStore =
        ExpressionPickerNavigationStores.navigation,
    storeStateObservable: Observable<StoreState> = observeStoreState(storeExpressionPickerNavigation),
    expressionPickerEventObservable: Observable<ExpressionPickerEvent> =
        ExpressionPickerEventBus.INSTANCE.observeExpressionPickerEvents(),
) : AppViewModel<ExpressionTrayViewModel.ViewState>(
    ViewState(
        selectedExpressionTab = ExpressionTrayTab.EMOJI,
        expressionDetailPage = null,
        showStickersSearchBar = true,
        showGifsAndStickers = true,
    ),
) {

    data class StoreState(
        val selectedExpressionTab: ExpressionTrayTab,
        val guildId: Long? = null,
        val meUser: MeUser? = null,
        val guildStickers: Map<Long, Map<Long, Sticker>>,
        val userGuildIds: Set<Long>,
        val isThreadDraft: Boolean,
    )

    data class ViewState(
        val selectedExpressionTab: ExpressionTrayTab,
        val expressionDetailPage: ExpressionDetailPage? = null,
        val showStickersSearchBar: Boolean,
        val showGifsAndStickers: Boolean,
    ) {
        /** A detail page replaces the tabs entirely rather than stacking on top of them. */
        val showLandingPage: Boolean = expressionDetailPage == null

        /**
         * The sticker picker hides the tray's search field while it is showing an empty state, so
         * for that tab the picker decides; emoji and GIF always want it.
         */
        val showSearchBar: Boolean = when (selectedExpressionTab) {
            ExpressionTrayTab.EMOJI, ExpressionTrayTab.GIF -> true
            ExpressionTrayTab.STICKER -> showStickersSearchBar
        }
    }

    /** Things only the fragment can carry out: showing a sheet, collapsing the tray. */
    sealed class Event {
        object HideExpressionTray : Event()
        object ShowEmojiPickerSheet : Event()
        object ShowGifPickerSheet : Event()

        data class ShowStickerPicker(
            val stickerPackId: Long?,
            val searchText: String?,
            val inline: Boolean = false,
        ) : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()

    /** Whether the tray is the visible surface; gates the "viewed" analytics so it fires once. */
    private var wasActive = false

    init {
        storeStateObservable
            .ui(this)
            .appSubscribe(ExpressionTrayViewModel::class.java, onNext = ::handleStoreState)
        expressionPickerEventObservable
            .ui(this)
            .appSubscribe(ExpressionTrayViewModel::class.java, onNext = ::handleExpressionPickerEvents)
    }

    fun observeEvents(): Observable<Event> = eventSubject

    private fun handleStoreState(storeState: StoreState) {
        updateViewState(
            requireViewState().copy(
                selectedExpressionTab = storeState.selectedExpressionTab,
                showGifsAndStickers = !storeState.isThreadDraft,
            ),
        )
    }

    private fun handleExpressionPickerEvents(expressionPickerEvent: ExpressionPickerEvent) {
        when (expressionPickerEvent) {
            ExpressionPickerEvent.CloseExpressionPicker -> hideExpressionPicker()

            is ExpressionPickerEvent.OpenStickerPicker -> {
                if (!expressionPickerEvent.inline) {
                    showStickerPickerSheet(
                        expressionPickerEvent.stickerPackId,
                        expressionPickerEvent.searchText,
                    )
                }
                // The inline picker is always brought up too: a sheet dismissal should leave the
                // tray on the sticker tab rather than back on emoji. `-1` means "no pack".
                selectTab(ExpressionTrayTab.STICKER)
                showStickerPickerInline(
                    expressionPickerEvent.stickerPackId ?: NO_STICKER_PACK,
                    expressionPickerEvent.searchText,
                )
            }
        }
    }

    // region view actions

    fun selectTab(expressionTrayTab: ExpressionTrayTab) =
        storeExpressionPickerNavigation.onSelectTab(expressionTrayTab)

    fun selectGifCategory(gifCategoryItem: GifCategoryItem) {
        updateViewState(
            requireViewState().copy(expressionDetailPage = ExpressionDetailPage.GifCategoryPage(gifCategoryItem)),
        )
    }

    /** No-op when the landing page is already showing, so the tray does not swallow a real back. */
    fun clickBack() {
        val viewState = requireViewState()
        if (viewState.expressionDetailPage != null) {
            updateViewState(viewState.copy(expressionDetailPage = null))
        }
    }

    /** The tray's search field is a button: each tab opens its own full search surface. */
    fun clickSearch(searchText: String? = null) {
        when (requireViewState().selectedExpressionTab) {
            ExpressionTrayTab.EMOJI -> eventSubject.onNext(Event.ShowEmojiPickerSheet)
            ExpressionTrayTab.GIF -> eventSubject.onNext(Event.ShowGifPickerSheet)
            ExpressionTrayTab.STICKER -> showStickerPickerSheet(null, searchText)
        }
    }

    fun showStickersSearchBar(show: Boolean) {
        updateViewState(requireViewState().copy(showStickersSearchBar = show))
    }

    /** Called by the tray when it becomes (or stops being) the visible surface. */
    fun handleIsActive(isActive: Boolean) {
        if (isActive && !wasActive) {
            viewState?.selectedExpressionTab?.let {
                AnalyticsTracker.chatInputComponentViewed(chatInputComponentType(it))
            }
        }
        wasActive = isActive
    }

    // endregion

    /**
     * A tab change counts as a component view, but only while the tray is on screen and only on the
     * landing page — drilling into a GIF category is not a tab change.
     */
    override fun updateViewState(viewState: ViewState) {
        val previousTab = this.viewState?.selectedExpressionTab
        val nextTab = viewState.selectedExpressionTab
        if (viewState.showLandingPage && previousTab != nextTab && wasActive) {
            AnalyticsTracker.chatInputComponentViewed(chatInputComponentType(nextTab))
        }
        super.updateViewState(viewState)
    }

    private fun hideExpressionPicker() = eventSubject.onNext(Event.HideExpressionTray)

    private fun showStickerPickerSheet(stickerPackId: Long?, searchText: String?) {
        eventSubject.onNext(Event.ShowStickerPicker(stickerPackId, searchText, inline = false))
    }

    private fun showStickerPickerInline(stickerPackId: Long?, searchText: String?) {
        eventSubject.onNext(Event.ShowStickerPicker(stickerPackId, searchText, inline = true))
    }

    private fun chatInputComponentType(tab: ExpressionTrayTab): String = when (tab) {
        ExpressionTrayTab.EMOJI -> ChatInputComponentTypes.EMOJI
        ExpressionTrayTab.GIF -> ChatInputComponentTypes.GIF
        ExpressionTrayTab.STICKER -> ChatInputComponentTypes.STICKER
    }

    companion object {
        /** The reference's sentinel for "open the sticker picker, but at no particular pack". */
        const val NO_STICKER_PACK = -1L

        fun observeStoreState(
            storeExpressionPickerNavigation: ExpressionPickerNavigationStore =
                ExpressionPickerNavigationStores.navigation,
            storeChannelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
            storeUser: StoreUser = StoreStream.getUsers(),
            storeGuildStickers: GuildStickerStore = StickerStores.guildStickers,
            storeGuilds: StoreGuilds = StoreStream.getGuilds(),
        ): Observable<StoreState> = Observable.combineLatest(
            storeExpressionPickerNavigation.observeSelectedTab(),
            storeChannelsSelected.observeResolvedSelectedChannel(),
            storeUser.observeMe(),
            storeGuildStickers.observeGuildStickers(),
            storeGuilds.observeGuilds(),
        ) { lastSelectedTab, resolvedChannel, meUser, guildStickers, guilds ->
            StoreState(
                selectedExpressionTab = lastSelectedTab,
                guildId = resolvedChannel.channelOrParent?.guildId,
                meUser = meUser,
                guildStickers = guildStickers,
                userGuildIds = guilds.keys.toSet(),
                isThreadDraft = resolvedChannel is StoreChannelsSelected.ResolvedSelectedChannel.ThreadDraft,
            )
        }
    }
}
