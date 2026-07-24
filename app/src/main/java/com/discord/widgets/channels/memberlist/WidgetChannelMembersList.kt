package com.discord.widgets.channels.memberlist

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.stores.StoreStream
import com.discord.stores.StoreChannelMembers
import com.discord.panels.PanelState
import rx.Observable
import rx.Subscription

class WidgetChannelMembersList : Fragment(R.layout.widget_channel_members_list) {
    data class State(
        val channel: Channel?,
        val panelOpen: Boolean,
        val rows: List<ChannelMemberListRow>,
    )

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ChannelMembersListAdapter
    private var subscription: Subscription? = null
    private var state = State(null, false, emptyList())
    private var subscribedThread: Channel? = null
    private var panelChannel: Channel? = null
    private var panelOpen = false
    private val updateRange = Runnable { updateVisibleRange() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ChannelMembersListAdapter(onUserClicked, onAddMemberClicked, onJoinLeaveThreadClicked)
        recycler = view as RecyclerView
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                recyclerView.removeCallbacks(updateRange)
                recyclerView.postDelayed(updateRange, RANGE_DEBOUNCE_MS)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        subscription = (stateSource?.invoke() ?: defaultStateSource()).subscribe { next ->
            activity?.runOnUiThread { show(next) }
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
        val rows = next.rows.filterNot {
            (it is ChannelMemberListRow.AddMember && onAddMemberClicked == null) ||
                (it is ChannelMemberListRow.JoinLeaveThread && onJoinLeaveThreadClicked == null)
        }
        adapter.submitList(rows)
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
        channel?.let { onThreadSubscriptionChanged?.invoke(it, true) }
    }

    private fun notifyPanelOpen(channel: Channel?, open: Boolean) {
        onPanelOpenChanged?.invoke(channel, open) ?: StoreStream.updateMemberPanel(channel, open)
    }

    private fun defaultStateSource(): Observable<State> = Observable.combineLatest(
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
                ) { users, presences, me ->
                    State(channel, panelOpen, ChannelMemberListRows.privateChannel(channel, users, presences, currentUser = me))
                }
                isThread(channel) -> Observable.combineLatest(
                    StoreStream.getUsers().observeAllUsers(),
                    StoreStream.getGuilds().observeGuildMembers(channel.guildId),
                    StoreStream.getPresences().observeAllPresences(),
                    StoreStream.getGuilds().observeRoles(channel.guildId),
                    StoreStream.getGuilds().observeGuild(channel.guildId),
                ) { users, members, presences, roles, guild ->
                    State(channel, panelOpen, ChannelMemberListRows.thread(
                        channel.memberListId ?: channel.id.toString(),
                        guild?.ownerId ?: 0,
                        channel.memberIdsPreview.orEmpty().toSet(),
                        users,
                        members,
                        presences,
                        roles,
                    ))
                }
                else -> StoreStream.getGuilds().observeRoles(channel.guildId).switchMap { roles ->
                    val listId = StoreChannelMembers.computeMemberListId(channel, roles)
                    Observable.combineLatest(
                        Observable.combineLatest(
                            StoreStream.getChannelMembers().observe(channel.guildId, listId),
                            StoreStream.getUsers().observeAllUsers(),
                        ) { list, users -> list to users },
                        StoreStream.getGuilds().observeGuildMembers(channel.guildId),
                        StoreStream.getPresences().observeAllPresences(),
                        StoreStream.getGuilds().observeGuild(channel.guildId),
                    ) { lazyState, members, presences, guild ->
                        val (list, users) = lazyState
                        val rows = list?.let {
                            ChannelMemberListRows.guild(it, channel.guildId, guild?.ownerId ?: 0, users, members, presences, roles)
                        } ?: ChannelMemberListRows.loadedGuild(
                            listId, guild?.ownerId ?: 0,
                            users.filterKeys(members.keys::contains), members, presences, roles,
                        )
                        State(channel, panelOpen, rows)
                    }
                }
            }
        }

    companion object {
        @JvmField var stateSource: (() -> Observable<State>)? = null
        @JvmField var onGuildRangeChanged: ((Channel, IntRange) -> Unit)? = null
        @JvmField var onThreadSubscriptionChanged: ((Channel, Boolean) -> Unit)? = null
        @JvmField var onPanelOpenChanged: ((Channel?, Boolean) -> Unit)? = null
        @JvmField var onUserClicked: ((Long) -> Unit)? = null
        @JvmField var onAddMemberClicked: (() -> Unit)? = null
        @JvmField var onJoinLeaveThreadClicked: (() -> Unit)? = null

        private const val RANGE_DEBOUNCE_MS = 100L
        private fun isThread(channel: Channel) = channel.type == Channel.ANNOUNCEMENT_THREAD ||
            channel.type == Channel.PUBLIC_THREAD || channel.type == Channel.PRIVATE_THREAD
    }
}
