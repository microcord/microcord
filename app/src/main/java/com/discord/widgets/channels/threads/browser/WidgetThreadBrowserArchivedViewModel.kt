package com.discord.widgets.channels.threads.browser

import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildForumChannel
import com.discord.api.permission.Permission
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.ArchivedThreadsStore
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.BehaviorSubject

private data class ArchivedInput(
    val mode: WidgetThreadBrowserArchivedViewModel.ViewMode,
    val listing: ArchivedThreadsStore.ThreadListingState,
    val members: Map<Long, GuildMember>,
    val users: Map<Long, User>,
    val permissions: Long?,
    val channel: Channel?,
    val guild: Guild?,
)

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
        val isModerator: Boolean,
        val isError: Boolean,
        val canCreateThread: Boolean,
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
                StoreStream.getChannels().observeChannel(channelId),
                StoreStream.getGuilds().observeGuild(guildId),
            ) { listing, members, users, permissions, channel, guild ->
                ArchivedInput(selected, listing, members, users, permissions, channel, guild)
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
        val listing = input.listing
        val isModerator = PermissionUtils.can(Permission.MANAGE_THREADS, input.permissions) &&
            PermissionUtils.can(Permission.READ_MESSAGE_HISTORY, input.permissions)
        (listing as? ArchivedThreadsStore.ThreadListingState.Listing)?.threads
            ?.map(Channel::ownerId)
            ?.filter { it > 0 && (it !in input.users || it !in input.members) }
            ?.distinct()
            ?.forEach { StoreStream.getGuildMemberRequester().queueRequest(guildId, it) }
        StoreStream.getGuildMemberRequester().performQueuedRequests()
        val rows = when (listing) {
            ArchivedThreadsStore.ThreadListingState.Uninitialized -> listOf(WidgetThreadBrowserAdapter.Item.Loading())
            ArchivedThreadsStore.ThreadListingState.Error -> emptyList()
            is ArchivedThreadsStore.ThreadListingState.Listing -> buildItems(
                input.mode,
                listing,
                input.members,
                input.users,
                input.channel?.isGuildForumChannel() == true,
            )
        }
        canLoadMore = (listing as? ArchivedThreadsStore.ThreadListingState.Listing)?.let { it.hasMore && !it.isLoadingMore } == true
        updateViewState(
            ViewState(
                input.mode,
                rows,
                isModerator,
                listing === ArchivedThreadsStore.ThreadListingState.Error,
                WidgetThreadBrowserViewModel.canCreateThread(input.permissions, input.channel, input.guild),
            ),
        )
    }

    companion object {
        internal fun buildItems(
            viewMode: ViewMode,
            listing: ArchivedThreadsStore.ThreadListingState.Listing,
            members: Map<Long, GuildMember>,
            users: Map<Long, User>,
            isForumChannel: Boolean,
        ) = buildList<WidgetThreadBrowserAdapter.Item> {
            if (isForumChannel) add(WidgetThreadBrowserAdapter.Item.Warning())
            if (listing.threads.isNotEmpty()) {
                add(
                    WidgetThreadBrowserAdapter.Item.Header(
                        "header",
                        if (viewMode.visibility == VisibilityMode.PublicThreads) {
                            R.string.thread_browser_public_header
                        } else {
                            R.string.thread_browser_private_header
                        },
                        listing.threads.size,
                    ),
                )
                addAll(listing.threads.map { thread ->
                    WidgetThreadBrowserAdapter.Item.Thread(
                        ThreadBrowserThreadView.ThreadData.ArchivedThread(
                            thread,
                            members,
                            users[thread.ownerId],
                            if (viewMode.threadListingType == ArchivedThreadsStore.ThreadListingType.MY_ARCHIVED_PRIVATE_THREADS) {
                                ThreadBrowserThreadView.TimestampMode.CreatedAt
                            } else {
                                ThreadBrowserThreadView.TimestampMode.ArchivedAt
                            },
                        ),
                    )
                })
            }
            if (listing.isLoadingMore) add(WidgetThreadBrowserAdapter.Item.Loading())
        }
    }
}
