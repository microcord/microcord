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
import com.discord.stores.StoreStream
import com.discord.stores.StoreThreadMessages
import com.discord.utilities.rx.ui
import rx.Observable

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
            requestMissingMembers(it)
            updateViewState(buildState(it))
        }
    }

    private fun requestMissingMembers(state: StoreState) {
        val requester = StoreStream.getGuildMemberRequester()
        (state.activeJoinedThreads.values + state.activeThreads.values)
            .flatMap { thread ->
                listOfNotNull(thread.ownerId.takeIf { it > 0 }, state.threadStates[thread.id]?.mostRecentMessage?.author?.id)
            }
            .filter { it !in state.users || it !in state.guildMembers }
            .distinct()
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
            val joined = state.activeJoinedThreads.values
            val others = state.activeThreads.filterKeys { it !in state.activeJoinedThreads }.values
            val rows = buildList<WidgetThreadBrowserAdapter.Item> {
                if (state.channel?.isThreadOnlyChannel() == true) add(WidgetThreadBrowserAdapter.Item.Warning())
                if (joined.isNotEmpty()) {
                    add(WidgetThreadBrowserAdapter.Item.Header("joined", R.string.thread_browser_joined_header, joined.size))
                    addAll(joined.map { it.item() })
                }
                if (others.isNotEmpty()) {
                    add(WidgetThreadBrowserAdapter.Item.Header("other", R.string.thread_browser_other_header, others.size))
                    addAll(others.map { it.item() })
                }
            }
            return ViewState(
                if (joined.isEmpty() && others.isEmpty()) emptyList() else rows,
                WidgetThreadBrowserViewModel.canCreateThread(state.permissions, state.channel, state.guild),
            )
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
