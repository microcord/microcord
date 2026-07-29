package com.discord.widgets.channels.memberlist

import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.discord.app.AppFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.role.GuildRole
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.stores.StoreChannelMembers
import com.discord.panels.PanelState
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.widgets.channels.WidgetGroupInviteFriends
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.utilities.views.StickyHeaderItemDecoration
import rx.Observable
import rx.Subscription

class WidgetChannelMembersList : AppFragment(R.layout.widget_channel_members_list) {
    data class State(
        val channel: Channel?,
        val panelOpen: Boolean,
        val rows: List<ChannelMemberListRow>,
    )

    private data class ThreadBase(
        val users: Map<Long, User>,
        val members: Map<Long, GuildMember>,
        val presences: Map<Long, Presence>,
        val roles: Map<Long, GuildRole>,
        val guild: Guild?,
    )

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ChannelMembersListAdapter
    private lateinit var viewModel: WidgetChannelMembersListViewModel
    private var subscription: Subscription? = null
    private var state = State(null, false, emptyList())
    private var subscribedThread: Channel? = null
    private var panelChannel: Channel? = null
    private var panelOpen = false
    private val updateRange = Runnable { updateVisibleRange() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ChannelMembersListAdapter(
            onUserClicked ?: ::showUser,
            onAddMemberClicked ?: ::addGroupMember,
            onJoinLeaveThreadClicked ?: ::toggleThreadMembership,
            onUserLongClicked ?: ::showUser,
        )
        recycler = view as RecyclerView
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        StickyHeaderItemDecoration(adapter).also {
            recycler.addItemDecoration(it)
            it.blockClicks(recycler)
        }
        recycler.setHasFixedSize(true)
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                recyclerView.removeCallbacks(updateRange)
                recyclerView.postDelayed(updateRange, RANGE_DEBOUNCE_MS)
            }
        })
        viewModel = ViewModelProvider(viewModelStore, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return WidgetChannelMembersListViewModel(stateSource?.invoke() ?: defaultStateSource()) as T
            }
        })[WidgetChannelMembersListViewModel::class.java]
    }

    override fun onStart() {
        super.onStart()
        subscription = viewModel.observeViewState().subscribe { next ->
            activity?.runOnUiThread {
                when (next) {
                    WidgetChannelMembersListViewModel.ViewState.Empty -> show(State(null, false, emptyList()))
                    is WidgetChannelMembersListViewModel.ViewState.Loaded ->
                        show(State(next.channel, next.isOpen, next.listItems))
                }
            }
        }
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        recycler.removeCallbacks(updateRange)
        setThreadSubscribed(null)
        if (panelOpen) notifyPanelOpen(panelChannel, false)
        panelChannel = null
        panelOpen = false
        super.onStop()
    }

    private fun show(next: State) {
        state = next
        if (panelChannel?.id != next.channel?.id || panelOpen != next.panelOpen) {
            if (panelOpen && panelChannel?.id != next.channel?.id) notifyPanelOpen(panelChannel, false)
            notifyPanelOpen(next.channel, next.panelOpen)
            panelChannel = next.channel
            panelOpen = next.panelOpen
        }
        adapter.submitList(next.rows)
        val channel = next.channel
        setThreadSubscribed(channel?.takeIf { next.panelOpen && isThread(it) })
        if (next.panelOpen) recycler.post(updateRange)
    }

    private fun updateVisibleRange() {
        val channel = state.channel ?: return
        if (!state.panelOpen || isThread(channel)) return
        val manager = recycler.layoutManager as? LinearLayoutManager ?: return
        val first = manager.findFirstVisibleItemPosition().takeIf { it >= 0 } ?: return
        val last = manager.findLastVisibleItemPosition().takeIf { it >= first } ?: return
        val visible = last - first + 1
        val range = maxOf(0, first - visible)..(last + visible)
        onGuildRangeChanged?.invoke(channel, range) ?: StoreStream.updateMemberRange(channel, range)
    }

    private fun setThreadSubscribed(channel: Channel?) {
        if (subscribedThread?.id == channel?.id) return
        subscribedThread?.let { onThreadSubscriptionChanged?.invoke(it, false) }
        subscribedThread = channel
        channel?.let {
            onThreadSubscriptionChanged?.invoke(it, true)
                ?: StoreStream.getGuildSubscriptions().subscribeThread(it.guildId, it.id)
        }
    }

    private fun notifyPanelOpen(channel: Channel?, open: Boolean) {
        onPanelOpenChanged?.invoke(channel, open) ?: StoreStream.updateMemberPanel(channel, open)
    }

    private fun addGroupMember() {
        val channel = state.channel?.takeIf { it.type == Channel.GROUP_DM } ?: return
        WidgetGroupInviteFriends.launch(requireContext(), channel.id, "Group DM Member List")
    }

    private fun showUser(userId: Long) {
        val channel = state.channel
        WidgetUserSheet.show(parentFragmentManager, userId, channel?.id, channel?.guildId)
    }

    private fun toggleThreadMembership() {
        val channel = state.channel?.takeIf(::isThread) ?: return
        val joined = state.rows.filterIsInstance<ChannelMemberListRow.JoinLeaveThread>().firstOrNull()?.joined ?: return
        val request = if (joined) {
            RestAPI.api.leaveThread(channel.id, "Member List")
        } else {
            RestAPI.api.joinThread(channel.id, "Member List", RestAPIParams.EmptyBody())
        }
        request.restSubscribeOn().appSubscribe(WidgetChannelMembersList::class.java) { }
    }

    private fun defaultStateSource(): Observable<State> {
        val base = Observable.combineLatest(
        StoreStream.getChannelsSelected().observeSelectedChannel(),
        StoreStream.getNavigation().observeRightPanelState(),
    ) { channel, panelState -> channel to (panelState == PanelState.Open || panelState == PanelState.Opening) }
        .switchMap { (channel, panelOpen) ->
            when {
                channel == null -> Observable.just(State(null, panelOpen, emptyList()))
                channel.type == Channel.DM || channel.type == Channel.GROUP_DM -> Observable.combineLatest(
                    StoreStream.getUsers().observeAllUsers(),
                    StoreStream.getPresences().observeAllPresences(),
                    StoreStream.getUsers().observeMe(emitEmpty = true),
                    StoreStream.getApplicationStreaming().observeStreamsByUser(),
                ) { users, presences, me, streams ->
                    State(channel, panelOpen, ChannelMemberListRows.privateChannel(
                        channel,
                        users,
                        presences,
                        canAddMembers = channel.type == Channel.GROUP_DM,
                        currentUser = me,
                        streamingUserIds = streams.keys,
                    ))
                }
                isThread(channel) -> Observable.combineLatest(
                    Observable.combineLatest(
                        Observable.combineLatest(
                            StoreStream.getUsers().observeAllUsers(),
                            StoreStream.getGuilds().observeGuildMembers(channel.guildId),
                            ::Pair,
                        ),
                        Observable.combineLatest(
                            StoreStream.getPresences().observeAllPresences(),
                            StoreStream.getGuilds().observeRoles(channel.guildId),
                            ::Pair,
                        ),
                        StoreStream.getGuilds().observeGuild(channel.guildId),
                    ) { usersAndMembers, presencesAndRoles, guild ->
                        ThreadBase(
                            usersAndMembers.first,
                            usersAndMembers.second,
                            presencesAndRoles.first,
                            presencesAndRoles.second,
                            guild,
                        )
                    },
                    Observable.combineLatest(
                        StoreStream.getThreadMembers().observeThreadMembers(channel.id),
                        StoreStream.getThreadsJoined().observeJoinedThread(channel.id),
                        StoreStream.getApplicationStreaming().observeStreamsByUser(),
                    ) { memberIds, joined, streams -> Triple(memberIds, joined != null, streams.keys) },
                ) { base, thread ->
                    val requester = StoreStream.getGuildMemberRequester()
                    thread.first.filter { it !in base.users }.forEach { requester.queueRequest(channel.guildId, it) }
                    requester.performQueuedRequests()
                    State(channel, panelOpen, ChannelMemberListRows.thread(
                        channel.memberListId ?: channel.id.toString(),
                        base.guild?.ownerId ?: 0,
                        thread.first.ifEmpty { channel.memberIdsPreview.orEmpty().toSet() },
                        base.users,
                        base.members,
                        base.presences,
                        base.roles,
                        joined = thread.second,
                        streamingUserIds = thread.third,
                    ))
                }
                else -> StoreStream.getGuilds().observeRoles(channel.guildId).switchMap { roles ->
                    val listId = StoreChannelMembers.computeMemberListId(channel, roles)
                    Observable.combineLatest(
                        Observable.combineLatest(
                            StoreStream.getChannelMembers().observe(channel.guildId, listId),
                            StoreStream.getUsers().observeAllUsers(),
                            ::Pair,
                        ),
                        Observable.combineLatest(
                            StoreStream.getGuilds().observeGuildMembers(channel.guildId),
                            StoreStream.getPresences().observeAllPresences(),
                            StoreStream.getGuilds().observeGuild(channel.guildId),
                        ) { members, presences, guild -> Triple(members, presences, guild) },
                        StoreStream.getApplicationStreaming().observeStreamsByUser(),
                    ) { lazyState, guildState, streams ->
                        val (list, users) = lazyState
                        val (members, presences, guild) = guildState
                        val rows = list?.let {
                            ChannelMemberListRows.guild(
                                it,
                                channel.guildId,
                                guild?.ownerId ?: 0,
                                users,
                                members,
                                presences,
                                roles,
                                streams.keys,
                            )
                        } ?: if (members.isEmpty()) {
                            ChannelMemberListRows.loading(listId)
                        } else {
                            ChannelMemberListRows.loadedGuild(
                                listId, guild?.ownerId ?: 0,
                                users.filterKeys(members.keys::contains), members, presences, roles, streams.keys,
                            )
                        }
                        State(channel, panelOpen, rows)
                    }
                }
            }
        }
        return Observable.combineLatest(base, StoreStream.getUserRelationships().observe()) { current, relationships ->
            current.copy(rows = current.rows.filterNot { row ->
                row is ChannelMemberListRow.Member && relationships[row.user.id] == BLOCKED_RELATIONSHIP
            })
        }
    }

    companion object {
        @JvmField var stateSource: (() -> Observable<State>)? = null
        @JvmField var onGuildRangeChanged: ((Channel, IntRange) -> Unit)? = null
        @JvmField var onThreadSubscriptionChanged: ((Channel, Boolean) -> Unit)? = null
        @JvmField var onPanelOpenChanged: ((Channel?, Boolean) -> Unit)? = null
        @JvmField var onUserClicked: ((Long) -> Unit)? = null
        @JvmField var onUserLongClicked: ((Long) -> Unit)? = null
        @JvmField var onAddMemberClicked: (() -> Unit)? = null
        @JvmField var onJoinLeaveThreadClicked: (() -> Unit)? = null

        private const val RANGE_DEBOUNCE_MS = 100L
        private const val BLOCKED_RELATIONSHIP = 2
        private fun isThread(channel: Channel) = channel.type == Channel.ANNOUNCEMENT_THREAD ||
            channel.type == Channel.PUBLIC_THREAD || channel.type == Channel.PRIVATE_THREAD
    }
}
