package com.discord.widgets.channels.threads.browser

import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildForumChannel
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.stores.ArchivedThreadsStore
import com.discord.stores.StoreStream
import com.discord.stores.StoreThreadMessages
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.BehaviorSubject

private data class ActiveThreads(
    val me: MeUser,
    val joined: Map<Long, Channel>,
    val active: Map<Long, Channel>,
    val messages: Map<Long, StoreThreadMessages.ThreadState>,
)

private data class ActiveEntities(
    val members: Map<Long, GuildMember>,
    val users: Map<Long, User>,
    val roles: Map<Long, GuildRole>,
    val channels: Map<Long, Channel>,
)

private data class ArchivedInput(
    val mode: WidgetThreadBrowserArchivedViewModel.ViewMode,
    val listing: ArchivedThreadsStore.ThreadListingState,
    val members: Map<Long, GuildMember>,
    val users: Map<Long, User>,
    val permissions: Long?,
)

class WidgetThreadBrowserViewModel(
    val guildId: Long,
    val channelId: Long,
    stateSource: Observable<StoreState> = Observable.combineLatest(
        StoreStream.getChannels().observeChannelsForGuild(guildId),
        StoreStream.getGuilds().observeGuild(guildId),
        StoreStream.getPermissions().observePermissionsForChannel(channelId),
    ) { channels, guild, permissions -> StoreState(channels[channelId], guild, permissions) },
) : AppViewModel<WidgetThreadBrowserViewModel.ViewState>() {
    data class StoreState(val channel: Channel?, val guild: Guild?, val permissions: Long?)
    sealed class ViewState {
        data class Browser(
            val channelName: String?,
            val canCreateThread: Boolean,
            val canViewArchivedThreads: Boolean,
            val isForumChannel: Boolean,
        ) : ViewState()
        data class GoToChannel(val channel: Channel) : ViewState()
    }

    init {
        stateSource.ui(this).subscribe { state ->
            val channel = state.channel
            updateViewState(ViewState.Browser(
                channel?.name,
                canCreateThread(state.permissions),
                PermissionUtils.can(Permission.READ_MESSAGE_HISTORY, state.permissions),
                channel?.isGuildForumChannel() == true,
            ))
        }
    }

    fun openThread(channel: Channel) = updateViewState(ViewState.GoToChannel(channel))

    companion object {
        internal fun canCreateThread(permissions: Long?) =
            PermissionUtils.can(Permission.CREATE_PUBLIC_THREADS, permissions) ||
                PermissionUtils.can(Permission.CREATE_PRIVATE_THREADS, permissions)
    }
}

class WidgetThreadBrowserActiveViewModel(
    private val guildId: Long,
    private val channelId: Long,
    stateSource: Observable<StoreState> = defaultSource(guildId, channelId),
) : AppViewModel<WidgetThreadBrowserActiveViewModel.ViewState>() {
    data class StoreState(
        val meUser: MeUser,
        val activeJoinedThreads: Map<Long, Channel>,
        val activeThreads: Map<Long, Channel>,
        val threadStates: Map<Long, StoreThreadMessages.ThreadState>,
        val guildMembers: Map<Long, GuildMember>,
        val users: Map<Long, User>,
        val guildRoles: Map<Long, GuildRole>,
        val channelNames: Map<Long, String>,
        val permissions: Long?,
        val blockedUsers: Map<Long, Int>,
        val channel: Channel?,
        val guild: Guild?,
    )
    data class ViewState(val listItems: List<WidgetThreadBrowserAdapter.Item>, val canCreateThread: Boolean)

    init {
        stateSource.ui(this).subscribe {
            requestMissingOwners(it.activeThreads.values, it.users)
            updateViewState(buildState(it))
        }
    }

    private fun requestMissingOwners(threads: Collection<Channel>, users: Map<Long, User>) {
        val requester = StoreStream.getGuildMemberRequester()
        threads.map(Channel::ownerId).filter { it > 0 && it !in users }.distinct()
            .forEach { requester.queueRequest(guildId, it) }
        requester.performQueuedRequests()
    }

    companion object {
        internal fun buildState(state: StoreState): ViewState {
            fun Channel.item() = WidgetThreadBrowserAdapter.Item.Thread(
                ThreadBrowserThreadView.ThreadData.ActiveThread(
                    this,
                    state.users[ownerId],
                    state.threadStates[id]?.mostRecentMessage,
                    state.meUser.id,
                    state.guildMembers,
                    state.guildRoles,
                    state.channelNames,
                    state.threadStates[id]?.mostRecentMessage?.author?.id?.let { state.blockedUsers[it] == 2 } == true,
                ),
            )
            val joined = state.activeJoinedThreads.values.sortedByDescending(Channel::id)
            val others = state.activeThreads.filterKeys { it !in state.activeJoinedThreads }.values.sortedByDescending(Channel::id)
            val rows = buildList<WidgetThreadBrowserAdapter.Item> {
                if (joined.isNotEmpty()) {
                    add(WidgetThreadBrowserAdapter.Item.Header("joined", R.string.thread_browser_joined_header, joined.size))
                    addAll(joined.map { it.item() })
                }
                if (others.isNotEmpty()) {
                    add(WidgetThreadBrowserAdapter.Item.Header("other", R.string.thread_browser_other_header, others.size))
                    addAll(others.map { it.item() })
                }
            }
            return ViewState(rows, WidgetThreadBrowserViewModel.canCreateThread(state.permissions))
        }

        private fun defaultSource(guildId: Long, channelId: Long): Observable<StoreState> =
            Observable.combineLatest(
                Observable.combineLatest(
                    StoreStream.getUsers().observeMe(emitEmpty = true),
                    StoreStream.getThreadsActiveJoined().observeActiveJoinedThreadsForChannel(guildId, channelId),
                    StoreStream.getThreadsActive().observeActiveThreadsForChannel(guildId, channelId),
                    StoreStream.getThreadMessages().observeThreadCountAndLatestMessage(),
                ) { me, joined, active, messages -> ActiveThreads(me, joined.mapValues { it.value.channel }, active, messages) },
                Observable.combineLatest(
                    StoreStream.getGuilds().observeGuildMembers(guildId),
                    StoreStream.getUsers().observeAllUsers(),
                    StoreStream.getGuilds().observeRoles(guildId),
                    StoreStream.getChannels().observeChannelsForGuild(guildId),
                ) { members, users, roles, channels -> ActiveEntities(members, users, roles, channels) },
                Observable.combineLatest(
                    StoreStream.getPermissions().observePermissionsForChannel(channelId),
                    StoreStream.getUserRelationships().observe(),
                    StoreStream.getGuilds().observeGuild(guildId),
                ) { permissions, relationships, guild -> Triple(permissions, relationships, guild) },
            ) { first, second, third ->
                StoreState(
                    first.me,
                    first.joined,
                    first.active,
                    first.messages,
                    second.members,
                    second.users,
                    second.roles,
                    second.channels.mapValues { it.value.name.orEmpty() },
                    third.first,
                    third.second,
                    second.channels[channelId],
                    third.third,
                )
            }
    }
}

class WidgetThreadBrowserArchivedViewModel(
    private val guildId: Long,
    private val channelId: Long,
    private val archivedThreads: ArchivedThreadsStore = StoreStream.getArchivedThreads(),
) : AppViewModel<WidgetThreadBrowserArchivedViewModel.ViewState>() {
    enum class VisibilityMode { PublicThreads, PrivateThreads }
    data class ViewMode(val visibility: VisibilityMode, val isModeratorMode: Boolean) {
        val threadListingType = when {
            visibility == VisibilityMode.PublicThreads -> ArchivedThreadsStore.ThreadListingType.ALL_ARCHIVED_PUBLIC_THREADS
            isModeratorMode -> ArchivedThreadsStore.ThreadListingType.ALL_ARCHIVED_PRIVATE_THREADS
            else -> ArchivedThreadsStore.ThreadListingType.MY_ARCHIVED_PRIVATE_THREADS
        }
    }
    data class ViewState(
        val viewMode: ViewMode,
        val listItems: List<WidgetThreadBrowserAdapter.Item>,
        val canCreateThread: Boolean,
        val isModerator: Boolean,
        val isError: Boolean,
    )

    private val mode = BehaviorSubject.create(ViewMode(VisibilityMode.PublicThreads, false))
    private var canLoadMore = false

    init {
        mode.distinctUntilChanged().switchMap { selected ->
            Observable.combineLatest(
                archivedThreads.loadAndObserveThreadListing(channelId, selected.threadListingType),
                StoreStream.getGuilds().observeGuildMembers(guildId),
                StoreStream.getUsers().observeAllUsers(),
                StoreStream.getPermissions().observePermissionsForChannel(channelId),
            ) { listing, members, users, permissions ->
                ArchivedInput(selected, listing, members, users, permissions)
            }
        }.ui(this).subscribe(::update)
    }

    fun onVisibilityChanged(visibility: VisibilityMode) = mode.onNext(mode.value.copy(visibility = visibility))
    fun onModeratorModeChanged(enabled: Boolean) = mode.onNext(mode.value.copy(isModeratorMode = enabled))

    @JvmOverloads
    fun maybeLoadMore(reload: Boolean = false) {
        if (!reload && !canLoadMore) return
        archivedThreads.fetchListing(channelId, mode.value.threadListingType, reload)
    }

    private fun update(input: ArchivedInput) {
        val viewMode = input.mode
        val listing = input.listing
        val isModerator = PermissionUtils.can(Permission.MANAGE_THREADS, input.permissions)
        (listing as? ArchivedThreadsStore.ThreadListingState.Listing)?.threads
            ?.map(Channel::ownerId)
            ?.filter { it > 0 && it !in input.users }
            ?.distinct()
            ?.forEach { StoreStream.getGuildMemberRequester().queueRequest(guildId, it) }
        StoreStream.getGuildMemberRequester().performQueuedRequests()
        val rows = when (listing) {
            ArchivedThreadsStore.ThreadListingState.Uninitialized -> listOf(WidgetThreadBrowserAdapter.Item.Loading())
            ArchivedThreadsStore.ThreadListingState.Error -> emptyList()
            is ArchivedThreadsStore.ThreadListingState.Listing -> buildList<WidgetThreadBrowserAdapter.Item> {
                if (viewMode.visibility == VisibilityMode.PrivateThreads && viewMode.isModeratorMode) {
                    add(WidgetThreadBrowserAdapter.Item.Warning())
                }
                addAll(listing.threads.map { thread ->
                    WidgetThreadBrowserAdapter.Item.Thread(ThreadBrowserThreadView.ThreadData.ArchivedThread(
                        thread,
                        input.members,
                        input.users[thread.ownerId],
                        if (viewMode.visibility == VisibilityMode.PublicThreads) {
                            ThreadBrowserThreadView.TimestampMode.ArchivedAt
                        } else {
                            ThreadBrowserThreadView.TimestampMode.CreatedAt
                        },
                    ))
                })
                if (listing.isLoadingMore) add(WidgetThreadBrowserAdapter.Item.Loading())
            }
        }
        canLoadMore = (listing as? ArchivedThreadsStore.ThreadListingState.Listing)?.let { it.hasMore && !it.isLoadingMore } == true
        updateViewState(ViewState(
            viewMode,
            rows,
            WidgetThreadBrowserViewModel.canCreateThread(input.permissions),
            isModerator,
            listing === ArchivedThreadsStore.ThreadListingState.Error,
        ))
    }
}
