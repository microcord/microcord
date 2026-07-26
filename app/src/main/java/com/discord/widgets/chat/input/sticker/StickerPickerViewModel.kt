package com.discord.widgets.chat.input.sticker

import androidx.annotation.MainThread
import com.discord.api.channel.Channel
import com.discord.api.channel.isPrivate
import com.discord.api.permission.Permission
import com.discord.api.sticker.Sticker
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.models.sticker.dto.ModelStickerPack
import com.discord.models.user.MeUser
import com.discord.stores.StoreChannels
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreGuildsSorted
import com.discord.stores.StoreMessages
import com.discord.stores.StorePermissions
import com.discord.stores.StoreSlowMode
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.StoreUserSettings
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.widgets.stickers.GuildStickerStore
import com.discord.widgets.stickers.StickerSendability
import com.discord.widgets.stickers.StickerStore
import com.discord.widgets.stickers.StickerStores
import com.discord.widgets.stickers.getStickerSendability
import java.util.Locale
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject

/**
 * Drives [WidgetStickerPicker].
 *
 * It turns "every sticker this user could conceivably send" into a flat, section-headed grid plus
 * the tab rail that indexes into it, filtered by the current search text. The ordering is fixed:
 * frequently used first, then each guild the user is in (current guild first), then the standard
 * packs — and the standard packs only appear at all for a Nitro subscriber, because nobody else can
 * send them.
 *
 * Selecting a sticker sends it from here rather than from the fragment, because whether the tap
 * *can* send depends on state ([StickerSendability], slow mode, write permission) that only this
 * class has.
 */
open class StickerPickerViewModel(
    private val locale: Locale,
    private val initialStickerPackId: Long,
    private val searchSubject: BehaviorSubject<String> = BehaviorSubject.create(""),
    private val selectedCategoryIdSubject: BehaviorSubject<Long> =
        BehaviorSubject.create(NO_CATEGORY_SELECTED),
    private val stickerStore: StickerStore = StickerStores.stickers,
    private val storeChannels: StoreChannels = StoreStream.getChannels(),
    private val storeChannelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
    private val storePermissions: StorePermissions = StoreStream.getPermissions(),
    private val storeUser: StoreUser = StoreStream.getUsers(),
    private val storeMessages: StoreMessages = StoreStream.getMessages(),
    storeStateObservable: Observable<StoreState> =
        observeStoreState(searchSubject, selectedCategoryIdSubject),
) : AppViewModel<StickerPickerViewModel.ViewState>() {

    data class StoreState(
        val allGuilds: Map<Long, Guild>,
        val guildStickers: Map<Long, Map<Long, Sticker>>,
        val enabledStickerPacks: List<ModelStickerPack>,
        val frequentlyUsedStickerIds: List<Long>,
        val searchQuery: String,
        val stickerAnimationSettings: Int,
        val selectedCategoryId: Long?,
        val meUser: MeUser,
        val isStickersSelectedTab: Boolean,
        val currentChannel: Channel?,
        val currentChannelPermissions: Long,
        val isOnCooldown: Boolean,
    ) {
        /**
         * Guild stickers from *other* servers need the external-stickers permission — except in a
         * DM, where there is no such permission to grant.
         */
        fun canUseExternalStickersInCurrentChannel(externalStickerGuildId: Long): Boolean {
            val channel = currentChannel
            if (channel != null && channel.isPrivate()) return true
            if (PermissionUtils.can(Permission.USE_EXTERNAL_STICKERS, currentChannelPermissions)) {
                return true
            }
            return channel != null && externalStickerGuildId == channel.guildId
        }
    }

    sealed class ViewState(open val searchQuery: String) {
        /** No stickers of any kind are reachable: the picker becomes a Nitro pitch. */
        data class EmptyNonPremium(
            val emptyStateStickers: List<Sticker>,
            val isStickersSelectedTab: Boolean,
        ) : ViewState("")

        data class EmptySearchResults(
            override val searchQuery: String,
            val categoryItems: List<StickerCategoryItem>,
        ) : ViewState(searchQuery)

        data class Stickers(
            override val searchQuery: String,
            val stickerItems: List<MGRecyclerDataPayload>,
            val categoryItems: List<StickerCategoryItem>,
            val isStickersSelectedTab: Boolean,
            val isOnCooldown: Boolean,
        ) : ViewState(searchQuery)
    }

    sealed class Event {
        data class ScrollToStickerItemPosition(val position: Int) : Event()

        object ShowStickerPremiumUpsell : Event()

        object SlowMode : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()

    init {
        stickerStore.fetchEnabledStickerDirectory()
        storeStateObservable
            .computationLatest()
            .ui(this)
            .distinctUntilChanged()
            .subscribe(::handleStoreState)
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun setSearchText(searchText: String) = searchSubject.onNext(searchText)

    fun setSelectedCategoryId(categoryId: Long) = selectedCategoryIdSubject.onNext(categoryId)

    /**
     * Sends [sticker] if it can be sent, and reports why not otherwise.
     *
     * @return whether the sticker actually left — the picker sheet only closes itself on `true`.
     */
    fun onStickerSelected(sticker: Sticker): Boolean {
        val viewState = viewState as? ViewState.Stickers ?: return false
        val channelId = storeChannelsSelected.getId()
        val channel = storeChannels.findChannelById(channelId) ?: return false
        val permissions = storePermissions.getPermissionsByChannel()[channelId]
        val meUser = storeUser.getMe()
        val sendability = getStickerSendability(sticker, meUser, channel, permissions)

        // Order matters, and matches the reference: the Nitro pitch wins over the slow-mode notice.
        if (sendability == StickerSendability.SENDABLE_WITH_PREMIUM) {
            eventSubject.onNext(Event.ShowStickerPremiumUpsell)
            return false
        }
        if (viewState.isOnCooldown) {
            eventSubject.onNext(Event.SlowMode)
            return false
        }
        // NONSENDABLE and SENDABLE_WITH_PREMIUM_GUILD fall through silently: the tile is already
        // dimmed, and there is nothing the user could do about either.
        if (!PermissionUtils.hasAccessWrite(channel, permissions)) return false
        if (sendability != StickerSendability.SENDABLE) return false

        stickerStore.onStickerUsed(sticker)
        storeMessages.sendMessage(
            channelId = channelId,
            author = meUser,
            content = "",
            stickers = listOf(sticker),
            guildId = channel.guildId.takeIf { it != 0L },
        ).subscribe({ }, { })
        searchSubject.onNext("")
        return true
    }

    /** Asks the view to scroll the grid to the header of [packId]; position 0 if it is not there. */
    fun scrollToPackId(packId: Long?) {
        val viewState = viewState as? ViewState.Stickers ?: return
        if (packId == null) return
        val position = viewState.stickerItems.indexOfFirst { item ->
            item is HeaderItem &&
                item.headerType is HeaderType.PackItem &&
                (item.headerType as HeaderType.PackItem).pack.id == packId
        }
        eventSubject.onNext(Event.ScrollToStickerItemPosition(position.coerceAtLeast(0)))
    }

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        // The pack the picker was opened on is only auto-scrolled to until the user picks a tab.
        val scrollTarget =
            if (storeState.selectedCategoryId == null) initialStickerPackId else null
        updateViewState(
            StickerPickerViewStateBuilder.build(storeState, locale) {
                stickerStore.getStickers()[it]
            },
        )
        scrollToPackId(scrollTarget)
    }

    companion object {
        /** The sentinel the "Frequently used" tab is selected by. */
        const val RECENT_SELECTED_ID = StickerCategoryItem.RECENT_CATEGORY_ID

        /** "the user has not chosen a tab yet", which is what enables the initial auto-scroll. */
        const val NO_CATEGORY_SELECTED = Long.MIN_VALUE

        private data class Catalog(
            val orderedGuilds: Map<Long, Guild>,
            val guildStickers: Map<Long, Map<Long, Sticker>>,
            val enabledStickerPacks: List<ModelStickerPack>,
            val frequentlyUsedStickerIds: List<Long>,
            val stickerAnimationSettings: Int,
        )

        private data class Query(
            val searchQuery: String,
            val selectedCategoryId: Long,
            val isStickersSelectedTab: Boolean,
        )

        private data class ChannelContext(
            val meUser: MeUser,
            val currentChannel: Channel?,
            val currentChannelPermissions: Long,
            val isOnCooldown: Boolean,
        )

        /**
         * @param isStickerTabSelected whether the expression tray is currently showing the sticker
         * tab. `widgets/chat/input/expression` — and with it `StoreExpressionPickerNavigation` —
         * has not been reconstructed, so this defaults to "yes": a picker that is on screen at all
         * is the visible tab in every surface that exists today. It is a parameter rather than a
         * global so the tray wave can hand in the real stream without touching call sites.
         */
        fun observeStoreState(
            searchSubject: Observable<String>,
            selectedCategoryIdSubject: Observable<Long>,
            stickerStore: StickerStore = StickerStores.stickers,
            guildStickerStore: GuildStickerStore = StickerStores.guildStickers,
            storeGuildsSorted: StoreGuildsSorted = StoreStream.getGuildsSorted(),
            storeUserSettings: StoreUserSettings = StoreStream.getUserSettings(),
            storeUser: StoreUser = StoreStream.getUsers(),
            storeChannelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
            storePermissions: StorePermissions = StoreStream.getPermissions(),
            storeSlowMode: StoreSlowMode = StoreStream.getSlowMode(),
            isStickerTabSelected: Observable<Boolean> = Observable.just(true),
        ): Observable<StoreState> =
            storeChannelsSelected.observeSelectedChannel().switchMap { channel ->
                val catalog = Observable.combineLatest(
                    orderedGuildsWithCurrentFirst(storeGuildsSorted, channel?.guildId),
                    guildStickerStore.observeGuildStickers(),
                    stickerStore.observeEnabledStickerPacks(),
                    stickerStore.observeFrequentlyUsedStickerIds(),
                    storeUserSettings.observeStickerAnimationSettings(),
                    ::Catalog,
                )
                val query = Observable.combineLatest(
                    searchSubject,
                    selectedCategoryIdSubject,
                    isStickerTabSelected,
                    ::Query,
                )
                val context = Observable.combineLatest(
                    storeUser.observeMe(),
                    storePermissions.observePermissionsForAllChannels(),
                    storeSlowMode.observeCooldownSecs(channel?.id, StoreSlowMode.Type.MessageSend),
                ) { meUser, permissions, cooldownSecs ->
                    ChannelContext(
                        meUser = meUser,
                        currentChannel = channel,
                        currentChannelPermissions = permissions[channel?.id ?: 0L] ?: 0L,
                        isOnCooldown = cooldownSecs > 0,
                    )
                }
                Observable.combineLatest(catalog, query, context) { c, q, ctx ->
                    StoreState(
                        allGuilds = c.orderedGuilds,
                        guildStickers = c.guildStickers,
                        enabledStickerPacks = c.enabledStickerPacks,
                        frequentlyUsedStickerIds = c.frequentlyUsedStickerIds,
                        searchQuery = q.searchQuery,
                        stickerAnimationSettings = c.stickerAnimationSettings,
                        selectedCategoryId = q.selectedCategoryId.takeIf { it != NO_CATEGORY_SELECTED },
                        meUser = ctx.meUser,
                        isStickersSelectedTab = q.isStickersSelectedTab,
                        currentChannel = ctx.currentChannel,
                        currentChannelPermissions = ctx.currentChannelPermissions,
                        isOnCooldown = ctx.isOnCooldown,
                    )
                }
            }

        /** The guild being viewed is pinned to the front of the rail; the rest keep their order. */
        private fun orderedGuildsWithCurrentFirst(
            storeGuildsSorted: StoreGuildsSorted,
            currentGuildId: Long?,
        ): Observable<Map<Long, Guild>> =
            storeGuildsSorted.observeOrderedGuilds().map { guilds ->
                val ordered = LinkedHashMap<Long, Guild>()
                currentGuildId?.let { id -> guilds[id]?.let { ordered[id] = it } }
                guilds.forEach { (id, guild) -> if (id !in ordered) ordered[id] = guild }
                ordered
            }.distinctUntilChanged()
    }
}

/** The picker embedded in the chat input's expression tray. */
class StickerPickerInlineViewModel(
    locale: Locale,
    initialStickerPackId: Long,
) : StickerPickerViewModel(locale, initialStickerPackId)

/**
 * The picker inside [WidgetStickerPickerSheet].
 *
 * A separate class purely so the sheet and the inline tray get *separate* activity-scoped instances
 * — otherwise typing a search in the sheet would rewrite the tray behind it.
 */
class StickerPickerSheetViewModel(
    locale: Locale,
    initialStickerPackId: Long,
) : StickerPickerViewModel(locale, initialStickerPackId)
