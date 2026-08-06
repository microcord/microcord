package com.discord.widgets.channels.threads.browser

import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.api.role.GuildRole
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.stores.ArchivedThreadsStore
import com.discord.stores.StoreStream
import com.discord.stores.StoreThreadMessages
import com.discord.utilities.rx.ui
import rx.Observable

private data class LiveThreads(
    val me: MeUser,
    val joined: Map<Long, Channel>,
    val active: Map<Long, Channel>,
    val messages: Map<Long, StoreThreadMessages.ThreadState>,
)

private data class ThreadEntities(
    val members: Map<Long, GuildMember>,
    val users: Map<Long, User>,
    val roles: Map<Long, GuildRole>,
    val channels: Map<Long, Channel>,
)

private data class ThreadContext(
    val permissions: Long?,
    val blockedUsers: Map<Long, Int>,
    val guild: Guild?,
    val older: ArchivedThreadsStore.ThreadListingState,
)

/**
 * Reference `ThreadList`.
 *
 * One list rather than an active/archived pager: joined threads, then the rest of the live ones,
 * then the older threads the channel has already archived — which the browser no longer calls
 * "archived". Older threads page in as the list is scrolled.
 */
class WidgetThreadBrowserListViewModel(
    private val guildId: Long,
    private val channelId: Long,
    private val archivedThreads: ArchivedThreadsStore = StoreStream.getArchivedThreads(),
    stateSource: Observable<StoreState>? = null,
) : AppViewModel<WidgetThreadBrowserListViewModel.ViewState>() {

    data class StoreState(
        val meUser: MeUser,
        val joinedThreads: Map<Long, Channel>,
        val liveThreads: Map<Long, Channel>,
        val threadStates: Map<Long, StoreThreadMessages.ThreadState>,
        val guildMembers: Map<Long, GuildMember>,
        val users: Map<Long, User>,
        val guildRoles: Map<Long, GuildRole>,
        val channelNames: Map<Long, String>,
        val permissions: Long?,
        val blockedUsers: Map<Long, Int>,
        val channel: Channel?,
        val guild: Guild?,
        val olderThreads: ArchivedThreadsStore.ThreadListingState,
    )

    data class ViewState(
        val listItems: List<WidgetThreadBrowserAdapter.Item>,
        val canCreateThread: Boolean,
        val isEmpty: Boolean,
    )

    private var canLoadMore = false

    init {
        (stateSource ?: defaultSource(guildId, channelId, archivedThreads)).ui(this).subscribe { state ->
            requestMissingMembers(state)
            canLoadMore = (state.olderThreads as? ArchivedThreadsStore.ThreadListingState.Listing)
                ?.let { it.hasMore && !it.isLoadingMore } == true
            updateViewState(buildState(state))
        }
    }

    fun maybeLoadMore(reload: Boolean = false) {
        if (!reload && !canLoadMore) return
        archivedThreads.fetchListing(channelId, OLDER_LISTING, reload)
    }

    private fun requestMissingMembers(state: StoreState) {
        val requester = StoreStream.getGuildMemberRequester()
        val older = (state.olderThreads as? ArchivedThreadsStore.ThreadListingState.Listing)?.threads.orEmpty()
        (state.joinedThreads.values + state.liveThreads.values + older)
            .flatMap { thread ->
                listOfNotNull(
                    thread.ownerId.takeIf { it > 0 },
                    state.threadStates[thread.id]?.mostRecentMessage?.author?.id,
                )
            }
            .filter { it !in state.users || it !in state.guildMembers }
            .distinct()
            .forEach { requester.queueRequest(guildId, it) }
        requester.performQueuedRequests()
    }

    companion object {
        /**
         * The reference asks for the channel's public archive at `LATEST_ACTIVITY` and offers no
         * visibility or tag filter in this surface.
         */
        private val OLDER_LISTING = ArchivedThreadsStore.ThreadListingType.ALL_ARCHIVED_PUBLIC_THREADS

        internal fun buildState(state: StoreState): ViewState {
            fun Channel.liveItem() = WidgetThreadBrowserAdapter.Item.Thread(
                ThreadBrowserThreadView.ThreadData.ActiveThread(
                    this,
                    state.users[ownerId],
                    state.threadStates[id]?.mostRecentMessage,
                    state.meUser.id,
                    state.guildMembers,
                    state.guildRoles,
                    state.channelNames,
                    state.threadStates[id]?.mostRecentMessage?.author?.id
                        ?.let { state.blockedUsers[it] == 2 } == true,
                ),
            )

            val joined = state.joinedThreads.values.toList()
            val live = state.liveThreads.filterKeys { it !in state.joinedThreads }.values.toList()
            val listing = state.olderThreads as? ArchivedThreadsStore.ThreadListingState.Listing
            val older = listing?.threads.orEmpty()
            val canCreateThread =
                WidgetThreadBrowserViewModel.canCreateThread(state.permissions, state.channel, state.guild)

            val rows = buildList<WidgetThreadBrowserAdapter.Item> {
                if (state.channel?.isThreadOnlyChannel() == true) add(WidgetThreadBrowserAdapter.Item.Warning())
                if (joined.isNotEmpty()) {
                    add(WidgetThreadBrowserAdapter.Item.Header("joined", R.string.thread_browser_joined_count, joined.size))
                    addAll(joined.map { it.liveItem() })
                }
                if (live.isNotEmpty()) {
                    add(WidgetThreadBrowserAdapter.Item.Header("active", R.string.thread_browser_active_count, live.size))
                    addAll(live.map { it.liveItem() })
                }
                if (older.isNotEmpty()) {
                    add(WidgetThreadBrowserAdapter.Item.Header("older", R.string.thread_browser_older_header, older.size))
                    addAll(
                        older.map { thread ->
                            WidgetThreadBrowserAdapter.Item.Thread(
                                ThreadBrowserThreadView.ThreadData.ArchivedThread(
                                    thread,
                                    state.guildMembers,
                                    state.users[thread.ownerId],
                                    ThreadBrowserThreadView.TimestampMode.ArchivedAt,
                                ),
                            )
                        },
                    )
                }
                if (listing == null || listing.isLoadingMore) add(WidgetThreadBrowserAdapter.Item.Loading())
            }

            val isEmpty = joined.isEmpty() && live.isEmpty() && older.isEmpty() &&
                state.olderThreads !is ArchivedThreadsStore.ThreadListingState.Uninitialized
            return ViewState(
                if (isEmpty) emptyList() else rows,
                canCreateThread,
                isEmpty,
            )
        }

        private fun defaultSource(
            guildId: Long,
            channelId: Long,
            archivedThreads: ArchivedThreadsStore,
        ): Observable<StoreState> = Observable.combineLatest(
            Observable.combineLatest(
                StoreStream.getUsers().observeMe(emitEmpty = true),
                StoreStream.getThreadsActiveJoined().observeActiveJoinedThreadsForChannel(guildId, channelId),
                StoreStream.getThreadsActive().observeActiveThreadsForChannel(guildId, channelId),
                StoreStream.getThreadMessages().observeThreadCountAndLatestMessage(),
            ) { me, joined, active, messages ->
                LiveThreads(me, joined.mapValues { it.value.channel }, active, messages)
            },
            Observable.combineLatest(
                StoreStream.getGuilds().observeGuildMembers(guildId),
                StoreStream.getUsers().observeAllUsers(),
                StoreStream.getGuilds().observeRoles(guildId),
                StoreStream.getChannels().observeChannelsForGuild(guildId),
            ) { members, users, roles, channels -> ThreadEntities(members, users, roles, channels) },
            Observable.combineLatest(
                StoreStream.getPermissions().observePermissionsForChannel(channelId),
                StoreStream.getUserRelationships().observe(),
                StoreStream.getGuilds().observeGuild(guildId),
                archivedThreads.loadAndObserveThreadListing(channelId, OLDER_LISTING),
            ) { permissions, relationships, guild, older ->
                ThreadContext(permissions, relationships, guild, older)
            },
        ) { live, entities, context ->
            StoreState(
                live.me,
                live.joined,
                live.active,
                live.messages,
                entities.members,
                entities.users,
                entities.roles,
                entities.channels.mapValues { it.value.name.orEmpty() },
                context.permissions,
                context.blockedUsers,
                entities.channels[channelId],
                context.guild,
                context.older,
            )
        }
    }
}
