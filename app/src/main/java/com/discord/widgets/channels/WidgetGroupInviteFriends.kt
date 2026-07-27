package com.discord.widgets.channels

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDMRecipient
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isDM
import com.discord.api.channel.isGroup
import com.discord.app.AppFragment
import com.discord.chips_view.ChipsView
import com.discord.databinding.WidgetGroupInviteFriendsBinding
import com.discord.i18n.i18nFormat
import com.discord.models.domain.ModelApplicationStream
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.presence.Presence
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.filterNull
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.isStaff
import com.discord.widgets.voice.call.callAndLaunch
import com.google.gson.internal.LinkedTreeMap
import java.util.ArrayList
import rx.Observable
import rx.subjects.BehaviorSubject

class WidgetGroupInviteFriends : AppFragment(R.layout.widget_group_invite_friends) {
    private var _binding: WidgetGroupInviteFriendsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private var adapter: WidgetGroupInviteFriendsAdapter? = null
    private val addedUsers = LinkedTreeMap<Long, User>()
    private val addedUsersPublisher = BehaviorSubject.create<Collection<User>>(emptyList())
    private val filterPublisher = BehaviorSubject.create("")

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetGroupInviteFriendsBinding.bind(view)
        adapter = MGRecyclerAdapter.configure(WidgetGroupInviteFriendsAdapter(binding.groupInviteFriendsRecycler))
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        setActionBarDisplayHomeAsUpEnabled()
        @Suppress("UNCHECKED_CAST")
        (binding.groupInviteFriendsChips as ChipsView<Long, UserDataContract>).apply {
            chipDeletedListener = { it?.modelUser?.let(::unselectUser) }
            textChangedListener = { filterPublisher.onNext(it.toString()) }
        }
        Model.getFriendChanges(addedUsersPublisher)
            .ui(this)
            .appSubscribe(javaClass) { addedUsersPublisher.onNext(it) }
        Model.get(
            mostRecentIntent.getLongExtra(INTENT_EXTRA_CHANNEL_ID, NO_CHANNEL_ID),
            addedUsersPublisher,
            filterPublisher,
        ).ui(this).appSubscribe(javaClass, onNext = ::configureUI)
    }

    override fun onDestroyView() {
        adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun configureUI(data: Model?) {
        if (data == null || data.mode == Model.MODE_ADD && data.channel == null) {
            appActivity?.finish()
            return
        }
        data.selectedUsers.forEach { user ->
            binding.groupInviteFriendsChips.b(
                user.username,
                getString(R.string.remove_role_or_user).replace("!!{name}!!", user.username),
                user.id,
                UserDataContract(user),
            )
        }
        if (!data.filterText.isNullOrEmpty() && binding.groupInviteFriendsChips.text.isEmpty()) {
            binding.groupInviteFriendsChips.text = data.filterText
        }
        binding.groupInviteFriendsChips.d(data.selectedUsers.mapTo(HashSet()) { it.id })

        val existingRecipients = data.channel?.recipients?.size ?: 0
        binding.groupInviteFriendsViewFlipper.displayedChild = getChildToDisplay(
            data.maxGroupMemberCount,
            existingRecipients + 1,
            data.potentialAdditions,
        )
        adapter?.setData(data.potentialAdditions) { user, selected ->
            if (!selected) {
                unselectUser(user)
            } else if (data.totalNumRecipients >= data.maxGroupMemberCount) {
                showGroupFullToast()
            } else {
                selectUser(user)
            }
        }
        setupFab(data)
        setupToolbar(data)
    }

    private fun selectUser(user: User) {
        binding.groupInviteFriendsChips.b(
            user.username,
            getString(R.string.remove_role_or_user).replace("!!{name}!!", user.username),
            user.id,
            UserDataContract(user),
        )
        addedUsers[user.id] = user
        addedUsersPublisher.onNext(ArrayList(addedUsers.values))
    }

    private fun unselectUser(user: User) {
        addedUsers.remove(user.id)
        addedUsersPublisher.onNext(ArrayList(addedUsers.values))
    }

    private fun setupFab(data: Model) {
        binding.groupInviteFriendsSaveFab.isVisible = data.selectedUsers.isNotEmpty()
        if (data.selectedUsers.isEmpty()) return
        binding.groupInviteFriendsSaveFab.setOnClickListener {
            if (data.totalNumRecipients > data.maxGroupMemberCount) {
                showGroupFullToast()
                return@setOnClickListener
            }
            val request = when {
                data.channel != null -> addGroupRecipients(data.channel.id, data.selectedUsers.toList())
                data.selectedUsers.size == 1 -> createOrFetchDm(data.selectedUsers.first().id)
                else -> createGroupDm(data.selectedUsers.map(User::id))
            }
            Observable.combineLatest(
                request,
                StoreStream.getVoiceChannelSelected().observeSelectedVoiceChannelId().takeSingleUntilTimeout(),
            ) { channel, selectedVoiceChannelId -> channel to selectedVoiceChannelId }
                .ui(this)
                .appSubscribe(javaClass, requireContext()) { (channel, selectedVoiceChannelId) ->
                    ChannelSelector.getInstance().selectChannel(0L, channel.id)
                    StoreStream.getTabsNavigation().selectHomeTab(StoreNavigation.PanelAction.CLOSE)
                    if (selectedVoiceChannelId == data.channel?.id) {
                        if (data.channel?.isGroup() == true) {
                            StoreStream.getCalls().ring(channel.id, data.selectedUsers.map(User::id))
                        } else {
                            StoreStream.getVoiceChannelSelected().clear()
                            StoreStream.getMediaEngine().selectVideoInputDevice(null)
                            callAndLaunch(
                                channel.id,
                                isVideo = false,
                                appPermissionsRequests = this,
                                context = requireContext(),
                                appComponent = this,
                                fragmentManager = parentFragmentManager,
                            )
                        }
                    }
                    appActivity?.onBackPressedDispatcher?.onBackPressed()
                }
        }
    }

    private fun setupToolbar(data: Model) {
        setActionBarTitle(
            data.channel?.let {
                requireContext().i18nFormat(
                    R.string.group_dm_invite_with_name,
                    "groupname" to it.getDisplayNameOrDefault(requireContext(), false),
                )
            } ?: getString(R.string.new_group_dm),
        )
        val subtitle = if (
            data.channel != null &&
            (data.channel.recipients?.size ?: 1) >= data.maxGroupMemberCount
        ) {
            binding.groupInviteFriendsRecipientsContainer.isVisible = false
            getString(R.string.group_dm_invite_full_main)
        } else {
            binding.groupInviteFriendsRecipientsContainer.isVisible = true
            val remaining = data.maxGroupMemberCount - data.totalNumRecipients
            when {
                remaining > 0 -> resources.getQuantityString(R.plurals.group_dm_invite_remaining_number, remaining)
                    .replace("{number}", remaining.toString())
                remaining == 0 -> getString(R.string.group_dm_invite_will_fill_mobile)
                else -> resources.getQuantityString(R.plurals.group_dm_invite_unselect_users_number, -remaining)
                    .replace("{number}", (-remaining).toString())
            }
        }
        setActionBarSubtitle(subtitle)
    }

    private fun showGroupFullToast() {
        Toast.makeText(requireContext(), R.string.group_dm_invite_full_sub, Toast.LENGTH_SHORT).show()
    }

    private fun createGroupDm(userIds: List<Long>): Observable<Channel> =
        RestAPI.api.userCreateChannel(RestAPIParams.CreateChannel(userIds))
            .restSubscribeOn()
            .doOnNext(StoreStream.getChannels()::onGroupCreated)

    private fun createOrFetchDm(userId: Long): Observable<Channel> =
        StoreStream.getChannels().observePrivateChannels().takeSingleUntilTimeout().flatMap { channels ->
            channels.values.firstOrNull { it.isDM() && it.getDMRecipient()?.id == userId }
                ?.let { Observable.just(it) }
                ?: RestAPI.api.userCreateChannel(RestAPIParams.CreateChannel(userId))
                    .restSubscribeOn()
                    .doOnNext { StoreStream.getGatewayConnection().channelCreateOrUpdate.onNext(it) }
        }

    private fun addGroupRecipients(channelId: Long, recipients: List<User>): Observable<Channel> =
        StoreStream.getChannels().observePrivateChannel(channelId)
            .filterNull()
            .takeSingleUntilTimeout()
            .flatMap { current ->
                if (current.isGroup()) Observable.just(current)
                else RestAPI.api.convertDMToGroup(channelId, recipients.first().id)
            }
            .flatMap { group ->
                val remaining = if (group.id == channelId) recipients else recipients.drop(1)
                Observable.from(remaining)
                    .flatMap { RestAPI.api.addChannelRecipient(group.id, it.id) }
                    .toList()
                    .map { group }
            }
            .flatMap { group -> StoreStream.getChannels().observeChannel(group.id).filterNull() }
            .takeSingleUntilTimeout()

    class UserDataContract(val modelUser: User?) : ChipsView.a {
        override fun getDisplayString(): String = modelUser?.username.orEmpty()
    }

    data class Model(
        val channel: Channel? = null,
        val filterText: String? = null,
        val selectedUsers: Collection<User>,
        val potentialAdditions: List<FriendItem>,
        val mode: Int = MODE_CREATE,
        val maxGroupMemberCount: Int,
    ) {
        val totalNumRecipients: Int
            get() = selectedUsers.size + 1 + (channel?.recipients?.size ?: 0)

        data class FriendItem(
            val user: User? = null,
            val presence: Presence? = null,
            val isSelected: Boolean = false,
            val isApplicationStreaming: Boolean = false,
        ) : MGRecyclerDataPayload {
            override val key: String = user?.id.toString()
            override val type: Int = TYPE_FRIEND

            companion object {
                const val TYPE_FRIEND = 0

                internal fun createData(
                    friends: ModelAppUserRelationship,
                    alreadyAddedUsers: Collection<User>,
                ): List<FriendItem> {
                    val selectedIds = alreadyAddedUsers.mapTo(HashSet()) { it.id }
                    return friends.users.keys.map { userId ->
                        FriendItem(
                            friends.users[userId],
                            friends.presences[userId],
                            userId in selectedIds,
                            userId in friends.streams,
                        )
                    }
                }
            }
        }

        data class ModelAppUserRelationship(
            val relationships: Map<Long, Int>,
            val presences: Map<Long, Presence>,
            val users: Map<Long, User>,
            val streams: Map<Long, ModelApplicationStream>,
        )

        companion object {
            const val MODE_CREATE = 0
            const val MODE_ADD = 1

            fun get(
                channelId: Long,
                addedUsersPublisher: Observable<Collection<User>>,
                filterPublisher: Observable<String>,
            ): Observable<Model?> = if (channelId == NO_CHANNEL_ID) {
                getForCreate(addedUsersPublisher, filterPublisher).map<Model?> { it }
            } else {
                getForAdd(channelId, addedUsersPublisher, filterPublisher)
            }

            fun getFriendChanges(addedUsers: Observable<Collection<User>>): Observable<out Collection<User>> =
                StoreStream.getUserRelationships().observeForType(ModelUserRelationship.TYPE_FRIEND)
                    .switchMap { friends ->
                        addedUsers.switchMap { users -> Observable.from(users).filter { it.id in friends }.toList() }
                    }
                    .distinctUntilChanged()

            private fun getForCreate(
                addedUsers: Observable<Collection<User>>,
                filter: Observable<String>,
            ): Observable<Model> = Observable.combineLatest(addedUsers, filter) { users, text ->
                AddedUsersInput(users, text)
            }
                .switchMap { input ->
                    getFilteredFriends(emptyList(), input.filter).map { friends ->
                        Model(
                            filterText = input.filter,
                            selectedUsers = input.addedUsers,
                            potentialAdditions = FriendItem.createData(friends, input.addedUsers),
                            maxGroupMemberCount = maxGroupMemberCount(),
                        )
                    }
                }

            private fun getForAdd(
                channelId: Long,
                addedUsers: Observable<Collection<User>>,
                filter: Observable<String>,
            ): Observable<Model?> = StoreStream.getChannels().observeChannel(channelId).switchMap { channel ->
                if (channel == null) return@switchMap Observable.just(null)
                Observable.combineLatest(addedUsers, filter) { users, text -> AddedUsersInput(users, text) }.switchMap { input ->
                    getFilteredFriends(
                        channel.recipients.orEmpty().map(::CoreUser),
                        input.filter,
                    ).map { friends ->
                        Model(
                            channel,
                            input.filter,
                            input.addedUsers,
                            FriendItem.createData(friends, input.addedUsers),
                            MODE_ADD,
                            maxGroupMemberCount(),
                        )
                    }
                }
            }

            private fun getFilteredFriends(
                excludedUsers: Collection<User>,
                nameFilter: String?,
            ): Observable<ModelAppUserRelationship> {
                val excludedIds = excludedUsers.mapTo(HashSet()) { it.id }
                return StoreStream.getUserRelationships().observeForType(ModelUserRelationship.TYPE_FRIEND)
                    .switchMap { relationships ->
                        val filtered = relationships.filterKeys { it !in excludedIds }
                        Observable.combineLatest(
                            StoreStream.getPresences().observePresencesForUsers(filtered.keys),
                            StoreStream.getUsers().observeUsers(filtered.keys),
                            StoreStream.getApplicationStreaming().observeStreamsByUser(),
                        ) { presences, users, streams ->
                            val matchingUsers = users.filterValues {
                                it.username.contains(nameFilter.orEmpty(), ignoreCase = true)
                            }
                            ModelAppUserRelationship(filtered, presences, matchingUsers, streams)
                        }
                    }
            }

            private fun maxGroupMemberCount(): Int =
                if (StoreStream.getUsers().getMe().isStaff) MAX_GROUP_MEMBERS_STAFF else MAX_GROUP_MEMBERS

            private data class AddedUsersInput(
                val addedUsers: Collection<User>,
                val filter: String,
            )
        }
    }

    companion object {
        private const val INTENT_EXTRA_CHANNEL_ID = "INTENT_EXTRA_CHANNEL_ID"
        private const val MAX_GROUP_MEMBERS = 10
        private const val MAX_GROUP_MEMBERS_STAFF = 25
        private const val NO_CHANNEL_ID = -1L
        internal const val VIEW_INDEX_FRIENDS_LIST = 0
        internal const val VIEW_INDEX_NO_FRIENDS = 1
        internal const val VIEW_INDEX_GROUP_FULL = 2

        internal fun getChildToDisplay(
            maxGroupSize: Int,
            numRecipients: Int,
            potentialAdditions: List<Model.FriendItem>?,
        ): Int = when {
            numRecipients >= maxGroupSize -> VIEW_INDEX_GROUP_FULL
            potentialAdditions.isNullOrEmpty() -> VIEW_INDEX_NO_FRIENDS
            else -> VIEW_INDEX_FRIENDS_LIST
        }

        fun launch(context: Context, source: String) {
            AnalyticsTracker.openModal("DM Group Create", source)
            com.discord.app.AppScreenRouter.launch(context, WidgetGroupInviteFriends::class.java)
        }

        fun launch(context: Context, channelId: Long, source: String) {
            AnalyticsTracker.openModal("DM Group Add", source)
            com.discord.app.AppScreenRouter.launch(
                context,
                WidgetGroupInviteFriends::class.java,
                Intent().putExtra(INTENT_EXTRA_CHANNEL_ID, channelId),
            )
        }
    }
}
