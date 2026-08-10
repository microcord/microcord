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
import com.discord.utilities.rest.RestAPIAbortMessages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.widgets.channels.WidgetGroupInviteFriends
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.utilities.views.StickyHeaderItemDecoration
import rx.Observable
import rx.Subscription

class WidgetChannelMembersList : AppFragment(R.layout.widget_channel_members_list) {
    data class State(
        val channel: Channel?,
        val panelOpen: Boolean,
        val listItems: WidgetChannelMembersListViewModel.MemberList,
        val isThreadJoined: Boolean = false,
    )

    private data class ThreadBase(
        val users: Map<Long, User>,
        val members: Map<Long, GuildMember>,
        val presences: Map<Long, Presence>,
        val roles: Map<Long, GuildRole>,
        val guild: Guild?,
    )

    private data class GuildBase(
        val members: Map<Long, GuildMember>,
        val presences: Map<Long, Presence>,
        val guild: Guild?,
        val permissions: Long?,
    )

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ChannelMembersListAdapter
    private lateinit var viewModel: WidgetChannelMembersListViewModel
    private var subscription: Subscription? = null
    private var eventSubscription: Subscription? = null
    private var state = State(null, false, EmptyMemberList)
    private val updateRange = Runnable { updateVisibleRange() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ChannelMembersListAdapter().apply {
            setOnUserClicked(onUserClicked ?: ::showUser)
            setOnUserLongClicked(onUserLongClicked ?: ::showUser)
            setOnAddMemberClicked(onAddMemberClicked ?: ::addGroupMember)
            setOnJoinLeaveThreadClicked(onJoinLeaveThreadClicked ?: ::toggleThreadMembership)
        }
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
                    WidgetChannelMembersListViewModel.ViewState.Empty -> show(State(null, false, EmptyMemberList))
                    is WidgetChannelMembersListViewModel.ViewState.Loaded ->
                        show(State(next.channel, next.isOpen, next.listItems, next.isThreadJoined))
                }
            }
        }
        eventSubscription = viewModel.observeEvents().subscribe { event ->
            activity?.runOnUiThread {
                when (event) {
                    is WidgetChannelMembersListViewModel.Event.Error -> {
                        val message = RestAPIAbortMessages.getAbortCodeMessageResId(event.code)
                            ?: R.string.network_error_bad_request
                        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    WidgetChannelMembersListViewModel.Event.ScrollToTop -> recycler.scrollToPosition(0)
                    WidgetChannelMembersListViewModel.Event.UpdateRanges -> recycler.post(updateRange)
                }
            }
        }
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        eventSubscription?.unsubscribe()
        eventSubscription = null
        recycler.removeCallbacks(updateRange)
        super.onStop()
    }

    private fun show(next: State) {
        state = next
        adapter.setData(next.listItems.listId, next.listItems, true)
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
        onGuildRangeChanged?.invoke(channel, range) ?: viewModel.updateSubscriptionsForChannel(range)
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
        val joined = state.isThreadJoined
        val request = if (joined) {
            RestAPI.api.leaveThread(channel.id, "Member List")
        } else {
            RestAPI.api.joinThread(channel.id, "Member List", RestAPIParams.EmptyBody())
        }
        request.restSubscribeOn().appSubscribe(
            WidgetChannelMembersList::class.java,
            errorHandler = { viewModel.emitError(it.code) },
        ) { }
    }

    private fun defaultStateSource(): Observable<State> {
        val privateChannelMembers = PrivateChannelMemberListService()
        val base = Observable.combineLatest(
        StoreStream.getChannelsSelected().observeSelectedChannel(),
        StoreStream.getNavigation().observeRightPanelState(),
    ) { channel, panelState -> channel to (panelState == PanelState.Open || panelState == PanelState.Opening) }
        .switchMap { (channel, panelOpen) ->
            when {
                channel == null -> Observable.just(State(null, panelOpen, EmptyMemberList))
                channel.type == Channel.DM || channel.type == Channel.GROUP_DM ->
                    privateChannelMembers.observeStateForGroupDm(channel).map { privateState ->
                        val me = privateState.users[StoreStream.getUsers().getMe().id]
                        State(
                            channel,
                            panelOpen,
                            generateGroupDmMemberListItems(
                                channel,
                                privateState.users,
                                privateState.presences,
                                privateState.applicationStreams,
                                canInvite = channel.type == Channel.GROUP_DM,
                                currentUser = me,
                            ),
                        )
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
                    ) { memberIds, joined, streams -> Triple(memberIds, joined, streams) },
                ) { base, thread ->
                    val requester = StoreStream.getGuildMemberRequester()
                    thread.first.filter { it !in base.users }.forEach { requester.queueRequest(channel.guildId, it) }
                    requester.performQueuedRequests()
                    State(
                        channel,
                        panelOpen,
                        generateThreadMemberListItems(
                            channel,
                            base.roles,
                            base.guild,
                            base.members,
                            base.users,
                            base.presences,
                            thread.third,
                            thread.first,
                            thread.second,
                            StoreStream.getGuilds(),
                            StoreStream.getCustomEmojis(),
                        ),
                        isThreadJoined = thread.second != null,
                    )
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
                            StoreStream.getPermissions().observePermissionsForChannel(channel.id),
                        ) { members, presences, guild, permissions ->
                            GuildBase(members, presences, guild, permissions)
                        },
                        StoreStream.getApplicationStreaming().observeStreamsByUser(),
                    ) { lazyState, guildState, streams ->
                        val (list, users) = lazyState
                        val items = list?.let {
                            generateGuildMemberListItems(
                                it,
                                guildState.guild,
                                channel,
                                roles,
                                canInvite = com.discord.utilities.permissions.PermissionUtils.can(
                                    com.discord.api.permission.Permission.CREATE_INSTANT_INVITE,
                                    guildState.permissions,
                                ),
                                isThreadJoined = false,
                                storeGuilds = StoreStream.getGuilds(),
                                storeCustomEmojis = StoreStream.getCustomEmojis(),
                                users = users,
                                guildMembers = guildState.members,
                                presences = guildState.presences,
                                streamingUserIds = streams.keys,
                            )
                        } ?: PrivateChannelMemberListItems(
                            listId,
                            ChannelMemberListRows.loading(listId).map(ChannelMemberListRow::toAdapterItem),
                        )
                        State(channel, panelOpen, items)
                    }
                }
            }
        }
        return base
    }

    companion object {
        @JvmField var stateSource: (() -> Observable<State>)? = null
        @JvmField var onGuildRangeChanged: ((Channel, IntRange) -> Unit)? = null
        @JvmField var onUserClicked: ((Long) -> Unit)? = null
        @JvmField var onUserLongClicked: ((Long) -> Unit)? = null
        @JvmField var onAddMemberClicked: (() -> Unit)? = null
        @JvmField var onJoinLeaveThreadClicked: (() -> Unit)? = null

        private const val RANGE_DEBOUNCE_MS = 100L
        private object EmptyMemberList : WidgetChannelMembersListViewModel.MemberList {
            override val listId = ""
            override val size = 0
            override fun get(index: Int) = throw IndexOutOfBoundsException("Index: $index")
            override fun getHeaderPositionForItem(itemPosition: Int): Int? = null
        }
        private fun isThread(channel: Channel) = channel.type == Channel.ANNOUNCEMENT_THREAD ||
            channel.type == Channel.PUBLIC_THREAD || channel.type == Channel.PRIVATE_THREAD
    }
}
