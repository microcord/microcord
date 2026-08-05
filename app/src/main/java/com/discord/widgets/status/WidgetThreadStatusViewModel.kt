package com.discord.widgets.status

import com.discord.api.channel.Channel
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.api.channel.isThread
import com.discord.api.permission.Permission
import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreChannels
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreThreadsActiveJoined
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.filterNull
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject

/**
 * State for the banner shown above a thread the user is looking at but has not joined, or that has
 * been archived.
 *
 * The banner has exactly three shapes — hidden, "join this thread", and "this thread is archived" —
 * and which one applies is a pure function of the selected channel, its parent, the user's
 * permissions in it, and whether the thread is in the joined set. [handleStoreState] is that
 * function; everything else here is the two REST actions the banner can start.
 */
class WidgetThreadStatusViewModel(
    val storeStateObservable: Observable<StoreState> = observeStoreState(),
) : AppViewModel<WidgetThreadStatusViewModel.ViewState>() {

    /** What the banner should say, if anything. */
    sealed class Status {
        /** The banner is not applicable: not a thread, already joined, or a redesigned forum post. */
        object Hide : Status()

        /** A live thread the user has not joined. */
        object Unjoined : Status()

        /**
         * An archived thread. [canArchive] drives whether the un-archive button is offered at all —
         * a locked thread additionally requires MANAGE_THREADS.
         */
        data class Archived(val isLocked: Boolean, val canArchive: Boolean) : Status()
    }

    data class StoreState(
        /** Parent channel id → thread id → membership row, for the selected channel's guild. */
        val activeJoinedThreads: Map<Long, Map<Long, StoreThreadsActiveJoined.ActiveJoinedThread>>,
        val selectedChannel: Channel?,
        val parentChannel: Channel?,
        val canArchive: Boolean,
        val isModerator: Boolean,
    )

    data class ViewState(val threadStatus: Status, val isLoading: Boolean = false)

    private val eventSubject = PublishSubject.create<WidgetThreadStatus.Event>()
    private var currentStoreState: StoreState? = null

    init {
        storeStateObservable
            .ui(this)
            .appSubscribe(WidgetThreadStatusViewModel::class.java) { handleStoreState(it) }
    }

    fun observeEvents(): Observable<WidgetThreadStatus.Event> = eventSubject

    private fun handleStoreState(storeState: StoreState) {
        currentStoreState = storeState
        val selectedChannel = storeState.selectedChannel
        val parentChannel = storeState.parentChannel
        val isJoined = storeState.activeJoinedThreads[selectedChannel?.parentId]
            ?.get(selectedChannel?.id) != null
        val metadata = selectedChannel?.threadMetadata

        val status = when {
            selectedChannel == null || !selectedChannel.isThread() -> Status.Hide
            // A forum post carries its own status bar, so this one stands down.
            parentChannel != null && parentChannel.isThreadOnlyChannel() -> Status.Hide
            metadata?.archived == true -> Status.Archived(
                isLocked = metadata.locked,
                canArchive = storeState.canArchive,
            )
            isJoined -> Status.Hide
            else -> Status.Unjoined
        }
        updateViewWithStatus(status)
    }

    private fun updateViewWithStatus(status: Status) = updateViewState(ViewState(status))

    private fun updateViewLoading(isLoading: Boolean) {
        viewState?.let { updateViewState(it.copy(isLoading = isLoading)) }
    }

    private fun emitError(code: Int) = eventSubject.onNext(WidgetThreadStatus.Event.Error(code))

    fun onJoinTapped() {
        val channel = currentStoreState?.selectedChannel ?: return
        RestAPI.api.joinThread(channel.id, JOIN_LOCATION, RestAPIParams.EmptyBody())
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetThreadStatusViewModel::class.java,
                errorHandler = { emitError(it.code) },
                onTerminated = { updateViewLoading(false) },
            ) { }
        updateViewLoading(true)
    }

    fun onUnarchiveTapped() {
        val storeState = currentStoreState ?: return
        val channel = storeState.selectedChannel ?: return
        // Only a moderator may clear the lock; everyone else leaves it untouched by sending null.
        val settings = RestAPIParams.ThreadSettings(
            archived = false,
            locked = if (storeState.isModerator) false else null,
        )
        RestAPI.api.editThread(channel.id, settings)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetThreadStatusViewModel::class.java,
                errorHandler = { emitError(it.code) },
                onTerminated = { updateViewLoading(false) },
            ) { }
        updateViewLoading(true)
    }

    companion object {
        /** Analytics location reported for a join started from this banner. */
        private const val JOIN_LOCATION = "Banner"

        /**
         * @param storeThreadsActiveJoined the joined-thread index. `StoreStream` does not construct
         *   this store yet, so the default is `null` and membership falls back to the thread
         *   member the gateway attaches to the channel itself. Pass the real store once the store
         *   graph owns one.
         */
        @JvmStatic
        fun observeStoreState(
            storeChannelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
            storeChannels: StoreChannels = StoreStream.getChannels(),
            storePermissions: StorePermissions = StoreStream.getPermissions(),
            storeThreadsActiveJoined: StoreThreadsActiveJoined? = StoreStream.getThreadsActiveJoined(),
        ): Observable<StoreState> =
            storeChannelsSelected.observeSelectedChannel()
                .filterNull()
                .switchMap { selectedChannel ->
                    Observable.combineLatest(
                        observeActiveJoinedThreads(storeThreadsActiveJoined, selectedChannel),
                        storePermissions.observePermissionsForChannel(selectedChannel.id),
                        storeChannels.observeChannel(selectedChannel.parentId),
                    ) { activeJoinedThreads, permissions, parentChannel ->
                        StoreState(
                            activeJoinedThreads = activeJoinedThreads,
                            selectedChannel = selectedChannel,
                            parentChannel = parentChannel,
                            canArchive = canUnarchiveThread(selectedChannel, permissions),
                            isModerator = PermissionUtils.can(Permission.MANAGE_THREADS, permissions),
                        )
                    }.distinctUntilChanged()
                }

        private fun observeActiveJoinedThreads(
            storeThreadsActiveJoined: StoreThreadsActiveJoined?,
            selectedChannel: Channel,
        ): Observable<Map<Long, Map<Long, StoreThreadsActiveJoined.ActiveJoinedThread>>> =
            storeThreadsActiveJoined?.observeActiveJoinedThreadsForGuild(selectedChannel.guildId)
                ?: Observable.just(joinedThreadsFromChannel(selectedChannel))

        /**
         * Membership derived from the channel alone: the gateway attaches the caller's thread
         * member to a thread it has joined, which is the same signal the joined-thread store is
         * built from.
         */
        private fun joinedThreadsFromChannel(
            channel: Channel,
        ): Map<Long, Map<Long, StoreThreadsActiveJoined.ActiveJoinedThread>> {
            val member = channel.member ?: return emptyMap()
            val row = StoreThreadsActiveJoined.ActiveJoinedThread(channel, member.joinTimestamp, member.muted)
            return mapOf(channel.parentId to mapOf(channel.id to row))
        }

        /**
         * Reference `ThreadUtils.canUnarchiveThread`. That utility object has no counterpart in this
         * project yet, so the predicate lives with its only current caller.
         */
        internal fun canUnarchiveThread(channel: Channel, channelPermissions: Long?): Boolean =
            if (channel.threadMetadata?.locked == true) {
                PermissionUtils.can(Permission.MANAGE_THREADS, channelPermissions) &&
                    PermissionUtils.hasAccessWrite(channel, channelPermissions)
            } else {
                PermissionUtils.hasAccessWrite(channel, channelPermissions)
            }
    }
}
