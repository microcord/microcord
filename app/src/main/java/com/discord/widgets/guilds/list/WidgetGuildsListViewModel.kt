package com.discord.widgets.guilds.list
import com.discord.models.guild.GeoRestrictedGuild

import android.content.Context
import androidx.fragment.app.FragmentManager
import com.discord.api.channel.Channel
import com.discord.api.guildjoinrequest.GuildJoinRequest
import com.discord.api.notification.AccountNotificationSettings
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelApplicationStream
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.guild.Guild
import com.discord.panels.PanelState
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreGuildsSorted
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.user.UserUtils.getAgeMs
import com.discord.widgets.guildscheduledevent.GuildIdsWithActiveEventsModel
import com.discord.widgets.guilds.list.GuildsListStoreHooks.GuildStream
import com.discord.widgets.stage.GuildIdsWithVisibleStageInstanceModel
import com.discord.widgets.tabs.NavigationTab
import rx.Observable
import rx.subjects.PublishSubject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

/**
 * Everything the guild sidebar shows and every gesture it accepts.
 *
 * The sidebar is one flat list, so folders are not a nesting level: an expanded folder is a folder
 * row followed by its guild rows, each tagged with `folderId`. That is what makes drag reordering
 * expressible as three plain operations ([moveAbove], [moveBelow], [target]) over a single list.
 *
 * A drag mutates only this view model's [ViewState.Loaded] — including the *preview* of a folder
 * merge, which is why [target] marks the target row rather than combining it. Nothing reaches the
 * stores or the API until [onDrop].
 */
class WidgetGuildsListViewModel(
    clock: Clock = ClockFactory.get(),
    storeObservable: Observable<StoreState> = observeStores(clock),
) : AppViewModel<WidgetGuildsListViewModel.ViewState>(ViewState.Uninitialized) {

    private val eventSubject = PublishSubject.create<Event>()

    /** The merge that would happen if the finger lifted now, or null when the drag is a reorder. */
    private var currentTargetOperation: WidgetGuildListAdapter.Operation.TargetOperation? = null

    private var wasLeftPanelOpened: Boolean = false
    private var wasOnHomeTab: Boolean = false

    init {
        storeObservable
            .computationLatest()
            .distinctUntilChanged()
            .ui(this)
            .appSubscribe(WidgetGuildsListViewModel::class.java) { handleStoreState(it) }
    }

    fun listenForEvents(): Observable<Event> = eventSubject

    // region store state

    private fun handleStoreState(storeState: StoreState) {
        val viewState = ViewState.Loaded(
            items = GuildListItemBuilder.buildItems(storeState),
            hasChannels = storeState.channelIds.isNotEmpty(),
            wasDragResult = false,
        )

        // Re-entering the sidebar — opening the panel, or coming back to the Home tab with it
        // already open — moves accessibility focus to the top of the list.
        if (storeState.isLeftPanelOpened != wasLeftPanelOpened || storeState.isOnHomeTab != wasOnHomeTab) {
            if (storeState.isLeftPanelOpened && storeState.isOnHomeTab) {
                eventSubject.onNext(Event.FocusFirstElement)
            }
            wasLeftPanelOpened = storeState.isLeftPanelOpened
            wasOnHomeTab = storeState.isOnHomeTab
        }

        updateViewState(viewState)
    }

    // endregion

    // region interaction

    fun onItemClicked(item: GuildListItem, context: Context, fragmentManager: FragmentManager) {
        when (item) {
            is GuildListItem.GuildItem -> when {
                // A guild that has not finished membership screening cannot be opened; the gate is
                // the only way in.
                item.isPendingGuild ->
                    GuildsListStoreHooks.showMemberVerificationGate(context, fragmentManager, item.guild.id)
                // Tapping the guild you are already in means "get me back to the content".
                item.isSelected -> StoreStream.getNavigation()
                    .setNavigationPanelAction(StoreNavigation.PanelAction.CLOSE)
                item.guild.isHub() && !GuildsListStoreHooks.isHubEnabled() ->
                    eventSubject.onNext(Event.ShowHubUnavailable)
                else -> StoreStream.selectGuild(item.guild.id)
            }
            is GuildListItem.PrivateChannelItem -> ChannelSelector.getInstance().selectChannel(0L, item.channel.id)
            is GuildListItem.UnavailableItem ->
                eventSubject.onNext(Event.ShowUnavailableGuilds(item.unavailableGuildCount))
            is GuildListItem.GeoRestrictedItem ->
                eventSubject.onNext(Event.ShowGeoRestrictedGuild(item.guild))
            is GuildListItem.FriendsItem -> if (item.isSelected) {
                StoreStream.getNavigation().setNavigationPanelAction(StoreNavigation.PanelAction.CLOSE)
            } else {
                StoreStream.selectGuild(0L)
            }
            GuildListItem.CreateItem -> eventSubject.onNext(Event.ShowCreateGuild)
            GuildListItem.HubItem -> eventSubject.onNext(Event.ShowHubVerification)
            GuildListItem.HelpItem -> eventSubject.onNext(Event.ShowHelp)
            is GuildListItem.FolderItem -> {
                val folders = StoreStream.getExpandedGuildFolders()
                if (item.isOpen) folders.closeFolder(item.itemId) else folders.openFolder(item.itemId)
                eventSubject.onNext(Event.AnnounceFolderToggleForAccessibility(item))
            }
            GuildListItem.DividerItem, GuildListItem.SpaceItem -> Unit
        }
    }

    /**
     * Guild and folder long-presses are handled by the fragment, which needs the touch point to
     * anchor the context menu; only the DM row's action sheet is routed through here.
     */
    fun onItemLongPressed(item: GuildListItem) {
        if (item is GuildListItem.PrivateChannelItem) {
            eventSubject.onNext(Event.ShowChannelActions(item.channel.id))
        }
    }

    // endregion

    // region drag

    /** Insert the dragged row above [targetPosition], joining that row's folder if it has one. */
    fun moveAbove(fromPosition: Int, targetPosition: Int) {
        val loaded = viewState as? ViewState.Loaded ?: return
        val folderId = when (val target = loaded.items[targetPosition]) {
            is GuildListItem.GuildItem -> target.folderId
            is GuildListItem.FolderItem, GuildListItem.HelpItem, GuildListItem.CreateItem -> null
            else -> error("invalid target")
        }
        move(fromPosition, targetPosition, folderId)
    }

    /**
     * Insert the dragged row below [targetPosition]. Below an *open* folder row means inside it —
     * that is the slot the user sees between the folder and its first child.
     */
    fun moveBelow(fromPosition: Int, targetPosition: Int) {
        val loaded = viewState as? ViewState.Loaded ?: return
        val folderId = when (val target = loaded.items[targetPosition]) {
            is GuildListItem.GuildItem -> target.folderId
            is GuildListItem.FolderItem -> target.folderId.takeIf { target.isOpen }
            else -> error("invalid target")
        }
        move(fromPosition, targetPosition + 1, folderId)
    }

    private fun move(fromPosition: Int, toPosition: Int, folderId: Long?) {
        val loaded = viewState as? ViewState.Loaded ?: return
        val items = loaded.items
        val movedItem = items[fromPosition]

        // Swapping with the immediately following row is a no-op unless it changes folder
        // membership, or unless a merge preview has to be rolled back first.
        val isRealMove = fromPosition > toPosition || abs(fromPosition - toPosition) >= 2
        val changesFolder = movedItem is GuildListItem.GuildItem && movedItem.folderId != folderId
        if (currentTargetOperation == null && !isRealMove && !changesFolder) return

        val editingList = ArrayList(items)
        untargetCurrentTarget(editingList)

        // Removing the row first shifts everything after it up by one.
        val insertAt = if (fromPosition < toPosition) toPosition - 1 else toPosition
        when (movedItem) {
            is GuildListItem.FolderItem -> {
                editingList.removeAt(fromPosition)
                editingList.add(insertAt, movedItem)
            }
            is GuildListItem.GuildItem -> {
                editingList.removeAt(fromPosition)
                editingList.add(insertAt, movedItem.copy(folderId = folderId))
                rebuildFolders(editingList, listOfNotNull(movedItem.folderId, folderId).toSet())
            }
            else -> Unit
        }
        updateViewState(loaded.copy(items = editingList, wasDragResult = true))
    }

    /** Marks [targetPosition] as the row a drop would merge into, and renders that preview. */
    fun target(fromPosition: Int, targetPosition: Int) {
        val loaded = checkNotNull(viewState as? ViewState.Loaded) { "targeting with no items" }
        val editingList = ArrayList(loaded.items)
        untargetCurrentTarget(editingList)

        editingList[targetPosition] = when (val target = editingList[targetPosition]) {
            is GuildListItem.GuildItem -> target.copy(isTargetedForFolderCreation = true)
            is GuildListItem.FolderItem -> target.copy(isTargetedForFolderAddition = true)
            else -> error("invalid target item: $target")
        }
        currentTargetOperation = WidgetGuildListAdapter.Operation.TargetOperation(fromPosition, targetPosition)
        updateViewState(loaded.copy(items = editingList, wasDragResult = true))
    }

    /** Clears the merge preview, in place, so the row renders as an ordinary guild or folder. */
    private fun untargetCurrentTarget(editingList: ArrayList<GuildListItem>) {
        val targetOperation = currentTargetOperation
        if (targetOperation != null) {
            val position = targetOperation.targetPosition
            editingList[position] = when (val target = editingList[position]) {
                is GuildListItem.GuildItem -> target.copy(isTargetedForFolderCreation = false)
                is GuildListItem.FolderItem -> target.copy(isTargetedForFolderAddition = false)
                else -> target
            }
        }
        currentTargetOperation = null
    }

    /**
     * Re-derives the `guilds` list of every folder in [changedFolderIds] from the guild rows that
     * currently claim it, so a folder row and its children can never disagree mid-drag.
     */
    private fun rebuildFolders(editingList: ArrayList<GuildListItem>, changedFolderIds: Set<Long>) {
        if (changedFolderIds.isEmpty()) return

        val guildsByFolder = HashMap<Long, MutableList<Guild>>()
        for (item in editingList) {
            if (item is GuildListItem.GuildItem) {
                val folderId = item.folderId ?: continue
                guildsByFolder.getOrPut(folderId) { ArrayList() }.add(item.guild)
            }
        }

        for (folderId in changedFolderIds) {
            val index = editingList.indexOfFirst {
                it is GuildListItem.FolderItem && it.folderId == folderId
            }
            if (index < 0) continue
            val folder = editingList[index] as GuildListItem.FolderItem
            editingList[index] = folder.copy(guilds = guildsByFolder[folderId] ?: emptyList())
        }
    }

    /**
     * Commits the drag: applies any pending merge, writes the new order to the store, and persists
     * it to the account.
     *
     * @return true when the drop merged the dragged row into another one, so the adapter knows the
     *   row is gone rather than snapping back.
     */
    fun onDrop(): Boolean {
        val loaded = viewState as? ViewState.Loaded ?: return false

        var items = loaded.items
        val targetOperation = currentTargetOperation
        if (targetOperation != null) {
            val editingList = ArrayList(items)
            untargetCurrentTarget(editingList)
            performTargetOperation(editingList, targetOperation.fromPosition, targetOperation.targetPosition)
            items = editingList
        }

        val entries = ArrayList<StoreGuildsSorted.Entry>()
        for (item in items) {
            when {
                item is GuildListItem.FolderItem ->
                    entries.add(StoreGuildsSorted.Entry.Folder(item.folderId, item.guilds, item.color, item.name))
                // Guilds inside a folder are already carried by their folder entry.
                item is GuildListItem.GuildItem && item.folderId == null ->
                    entries.add(StoreGuildsSorted.Entry.SingletonGuild(item.guild))
            }
        }

        StoreStream.getGuildsSorted().setPositions(entries)
        RestAPI.api
            .updateUserSettings(RestAPIParams.UserSettings.createWithGuildFolders(entries.map { it.asModelGuildFolder() }))
            .restSubscribeOn()
            .appSubscribe(WidgetGuildsListViewModel::class.java) { }

        return targetOperation != null
    }

    /** Guild onto guild creates a folder; guild onto folder joins it. */
    private fun performTargetOperation(
        editingList: ArrayList<GuildListItem>,
        fromPosition: Int,
        toPosition: Int,
    ) {
        val source = editingList[fromPosition]
        val target = editingList[toPosition]
        if (source !is GuildListItem.GuildItem) return

        when (target) {
            is GuildListItem.GuildItem -> {
                editingList[toPosition] = GuildListItem.FolderItem(
                    // A brand-new folder has no server-side id yet; the account settings write in
                    // onDrop is what makes this one real.
                    folderId = Random.nextLong(),
                    color = null,
                    name = null,
                    isOpen = false,
                    guilds = listOf(target.guild, source.guild),
                    isAnyGuildSelected = target.isSelected || source.isSelected,
                    isAnyGuildConnectedToVoice = target.isConnectedToVoice || source.isConnectedToVoice,
                    isAnyGuildConnectedToStageChannel =
                    target.isConnectedToStageChannel || source.isConnectedToStageChannel,
                    mentionCount = source.mentionCount + target.mentionCount,
                    isUnread = target.isUnread || source.isUnread,
                    isMentionLowImportance = target.isMentionLowImportance && source.isMentionLowImportance,
                )
                editingList.removeAt(fromPosition)
            }
            is GuildListItem.FolderItem -> {
                editingList[toPosition] = target.copy(
                    guilds = target.guilds + source.guild,
                    isAnyGuildSelected = target.isAnyGuildSelected || source.isSelected,
                    isAnyGuildConnectedToVoice = target.isAnyGuildConnectedToVoice || source.isConnectedToVoice,
                    isUnread = target.isUnread || source.isUnread,
                )
                editingList.removeAt(fromPosition)
            }
            else -> Unit
        }
    }

    // endregion

    sealed class Event {
        data class ShowChannelActions(val channelId: Long) : Event()
        data class ShowUnavailableGuilds(val unavailableGuildCount: Int) : Event()
        data class ShowGeoRestrictedGuild(val guild: GeoRestrictedGuild) : Event()
        data class AnnounceFolderToggleForAccessibility(val item: GuildListItem.FolderItem) : Event()
        object ShowCreateGuild : Event()
        object ShowHubVerification : Event()
        object ShowHelp : Event()
        object FocusFirstElement : Event()

        /**
         * Student hubs are desktop-only in this build. Not in the reference's event set — it
         * toasts straight from the view model, which a view model in this project must not do.
         */
        object ShowHubUnavailable : Event()
    }

    sealed class ViewState {
        /** Before the first store emission. The sidebar renders nothing rather than an empty list. */
        object Uninitialized : ViewState()

        /**
         * @param hasChannels false only for a brand-new account with no guild at all; it is what
         *   dismisses the "add a server" hint once the account stops being empty.
         * @param wasDragResult true when this state came from a drag rather than the stores, in
         *   which case the adapter must not animate a diff over what is already on screen.
         */
        data class Loaded(
            val items: List<GuildListItem>,
            val hasChannels: Boolean,
            val wasDragResult: Boolean,
        ) : ViewState()
    }

    /**
     * Everything the sidebar reads, in one snapshot.
     *
     * Assembled in two halves because RxJava 1's `combineLatest` tops out at nine sources.
     */
    data class StoreState(
        val selectedGuildId: Long,
        val selectedVoiceChannelId: Long,
        val guildSettings: Map<Long, ModelNotificationSettings>,
        val unreadGuildIds: Set<Long>,
        val unreadChannelIds: Set<Long>,
        val mentionCounts: Map<Long, Int>,
        val lowImportanceMentionChannels: Set<Long>,
        val accountNotificationSettings: AccountNotificationSettings,
        val channelIds: Map<Long, List<Long>>,
        val unavailableGuilds: Set<Long>,
        val geoRestrictedGuilds: List<GeoRestrictedGuild>,
        val privateChannels: Map<Long, Channel>,
        val sortedGuilds: List<StoreGuildsSorted.Entry>,
        val guildJoinRequests: Map<Long, GuildJoinRequest>,
        val pendingGuilds: List<Guild>,
        val guildIds: Set<Long>,
        val lurkingGuildIds: Set<Long>,
        val guildIdsWithActiveStageEvents: Set<Long>,
        val guildIdsWithActiveScheduledEvents: Set<Long>,
        val channels: Map<Long, Channel>,
        val openFolderIds: Set<Long>,
        val isNewUser: Boolean,
        val applicationStreams: List<GuildStream>,
        val allChannelPermissions: Map<Long, Long>,
        val isLeftPanelOpened: Boolean,
        val isOnHomeTab: Boolean,
    )

    companion object {

        /** Accounts younger than two weeks still get the help row. */
        private const val NEW_USER_AGE_MS = 1_209_600_000L

        /**
         * The sidebar redraws on nearly every store change, so the composed stream is throttled on
         * its leading edge: the first change renders immediately and the burst behind it collapses.
         */
        fun observeStores(clock: Clock): Observable<StoreState> = Observable.combineLatest(
            observeFirstChunk(),
            observeSecondChunk(),
            GuildsListStoreHooks.lurkingGuildIds(),
            StoreStream.getExpandedGuildFolders().observeOpenFolderIds(),
            StoreStream.getUsers().observeMe().distinctUntilChanged().map { it.getAgeMs(clock) < NEW_USER_AGE_MS },
            Observable.combineLatest(
                StoreStream.getApplicationStreaming().observeStreamsByUser(),
                StoreStream.getAuthentication().observeIsAuthed(),
            ) { streams, isAuthed ->
                if (!isAuthed) emptyList() else streams.values
                    .filterIsInstance<ModelApplicationStream.GuildStream>()
                    .map { GuildStream(it.guildId, it.channelId) }
            },
            StoreStream.getPermissions().observePermissionsForAllChannels(),
            StoreStream.getNavigation().observeLeftPanelState().map { it == PanelState.Open },
            StoreStream.getTabsNavigation().observeSelectedTab().map { it == NavigationTab.HOME },
        ) { first, second, lurkingGuildIds, openFolderIds, isNewUser, streams, permissions, isLeftPanelOpened, isOnHomeTab ->
            StoreState(
                selectedGuildId = first.selectedGuildId,
                selectedVoiceChannelId = first.selectedVoiceChannelId,
                guildSettings = first.guildSettings,
                unreadGuildIds = first.unreadGuildIds,
                unreadChannelIds = second.unreadChannelIds,
                mentionCounts = first.mentionCounts,
                lowImportanceMentionChannels = second.lowImportanceMentionChannels,
                accountNotificationSettings = second.accountNotificationSettings,
                channelIds = first.channelIds,
                unavailableGuilds = first.unavailableGuilds,
                geoRestrictedGuilds = first.geoRestrictedGuilds,
                privateChannels = first.privateChannels,
                sortedGuilds = first.sortedGuilds,
                guildJoinRequests = second.guildJoinRequests,
                pendingGuilds = second.pendingGuilds,
                guildIds = second.guildIds,
                lurkingGuildIds = lurkingGuildIds,
                guildIdsWithActiveStageEvents = second.guildIdsWithActiveStageEvents,
                guildIdsWithActiveScheduledEvents = second.guildIdsWithActiveScheduledEvents,
                channels = second.channels,
                openFolderIds = openFolderIds,
                isNewUser = isNewUser,
                applicationStreams = streams,
                allChannelPermissions = permissions,
                isLeftPanelOpened = isLeftPanelOpened,
                isOnHomeTab = isOnHomeTab,
            )
        }.leadingEdgeThrottle(100L, TimeUnit.MILLISECONDS)

        private fun observeFirstChunk(): Observable<Chunk> = Observable.combineLatest(
            StoreStream.getGuildSelected().observeSelectedGuildId(),
            StoreStream.getVoiceChannelSelected().observeSelectedVoiceChannelId(),
            StoreStream.getUserGuildSettings().observeGuildSettings(),
            StoreStream.getReadStates().getUnreadGuildIds(),
            // Mention counts churn per message; half a second of coalescing is invisible on a badge.
            StoreStream.getMentions().observeMentionCounts()
                .leadingEdgeThrottle(500L, TimeUnit.MILLISECONDS)
                .distinctUntilChanged(),
            StoreStream.getChannels().observeIds(true),
            Observable.combineLatest(
                StoreStream.getGuilds().observeUnavailableGuilds(),
                StoreStream.getGuilds().observeGeoRestrictedGuilds(),
            ) { unavailable, geoRestricted -> unavailable to geoRestricted },
            StoreStream.getChannels().observePrivateChannels(),
            StoreStream.getGuildsSorted().observeEntries(),
        ) { selectedGuildId, selectedVoiceChannelId, guildSettings, unreadGuildIds, mentionCounts,
            channelIds, availability, privateChannels, sortedGuilds ->
            Chunk(
                selectedGuildId = selectedGuildId,
                selectedVoiceChannelId = selectedVoiceChannelId,
                guildSettings = guildSettings,
                unreadGuildIds = unreadGuildIds,
                mentionCounts = mentionCounts,
                channelIds = channelIds,
                unavailableGuilds = availability.first,
                geoRestrictedGuilds = availability.second,
                privateChannels = privateChannels,
                sortedGuilds = sortedGuilds,
            )
        }

        private fun observeSecondChunk(): Observable<SecondChunk> = Observable.combineLatest(
            GuildsListStoreHooks.guildJoinRequests(),
            GuildsListStoreHooks.pendingGuilds(),
            StoreStream.getGuilds().observeGuildIds(),
            GuildIdsWithVisibleStageInstanceModel.observe(),
            GuildIdsWithActiveEventsModel.observe(),
            StoreStream.getChannels().observeAllChannels(),
            StoreStream.getReadStates().getUnreadChannelIds(),
            Observable.combineLatest(
                StoreStream.getUserGuildSettings().observeAccountNotificationSettings(),
                StoreStream.getMentions().observeLowImportanceMentionChannels(),
            ) { accountSettings, lowImportanceChannels -> accountSettings to lowImportanceChannels },
        ) { guildJoinRequests, pendingGuilds, guildIds, stageEvents, scheduledEvents, channels,
            unreadChannelIds, notifications ->
            SecondChunk(
                guildJoinRequests = guildJoinRequests,
                pendingGuilds = pendingGuilds,
                guildIds = guildIds,
                guildIdsWithActiveStageEvents = stageEvents,
                guildIdsWithActiveScheduledEvents = scheduledEvents,
                channels = channels,
                unreadChannelIds = unreadChannelIds,
                accountNotificationSettings = notifications.first,
                lowImportanceMentionChannels = notifications.second,
            )
        }

        private data class Chunk(
            val selectedGuildId: Long,
            val selectedVoiceChannelId: Long,
            val guildSettings: Map<Long, ModelNotificationSettings>,
            val unreadGuildIds: Set<Long>,
            val mentionCounts: Map<Long, Int>,
            val channelIds: Map<Long, List<Long>>,
            val geoRestrictedGuilds: List<GeoRestrictedGuild>,
            val unavailableGuilds: Set<Long>,
            val privateChannels: Map<Long, Channel>,
            val sortedGuilds: List<StoreGuildsSorted.Entry>,
        )

        private data class SecondChunk(
            val guildJoinRequests: Map<Long, GuildJoinRequest>,
            val pendingGuilds: List<Guild>,
            val guildIds: Set<Long>,
            val guildIdsWithActiveStageEvents: Set<Long>,
            val guildIdsWithActiveScheduledEvents: Set<Long>,
            val channels: Map<Long, Channel>,
                val unreadChannelIds: Set<Long>,
            val accountNotificationSettings: AccountNotificationSettings,
            val lowImportanceMentionChannels: Set<Long>,
        )
    }
}
