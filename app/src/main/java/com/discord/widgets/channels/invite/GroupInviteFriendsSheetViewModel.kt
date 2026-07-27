package com.discord.widgets.channels.invite

import androidx.annotation.MainThread
import com.discord.api.channel.Channel
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.user.User
import com.discord.stores.StoreChannels
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.StoreUserRelationships
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.isStaff
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject

class GroupInviteFriendsSheetViewModel @JvmOverloads constructor(
    private val channelId: Long,
    private val storeUser: StoreUser = StoreStream.getUsers(),
    private val maxNumMembers: Int = if (storeUser.getMe().isStaff) MAX_GROUP_MEMBERS_STAFF else MAX_GROUP_MEMBERS,
    storeStateObservable: Observable<StoreState> = observeStoreState(
        channelId,
        StoreStream.getUserRelationships(),
        storeUser,
        StoreStream.getChannels(),
    ),
) : AppViewModel<GroupInviteFriendsSheetViewModel.ViewState>() {

    private val checkedUsers = HashSet<User>()
    private var currentStoreState = StoreState()
    private val eventSubject = PublishSubject.create<Event>()
    private val searchTextChangedPublisher = BehaviorSubject.create<CharSequence>()

    init {
        storeStateObservable.ui(this).appSubscribe(javaClass, onNext = ::handleStoreState)
        searchTextChangedPublisher
            .debounce(SEARCH_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)
            .ui(this)
            .appSubscribe(javaClass, onNext = ::onSearch)
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun onSearchTextChanged(searchText: CharSequence) {
        searchTextChangedPublisher.onNext(searchText)
    }

    fun onChangeUserChecked(user: User, isChecked: Boolean) {
        val state = viewState ?: return
        if (isChecked) {
            if (checkedUsers.size < maxNumMembers) checkedUsers.add(user) else eventSubject.onNext(Event.ChannelFull)
        } else {
            checkedUsers.remove(user)
        }
        updateViewState(
            state.copy(
                checkedUsers = checkedUsers,
                friendItems = GroupInviteFriendsSheetAdapter.FriendItem.createItems(
                    state.friendItems.map { it.user },
                    checkedUsers,
                ),
            ),
        )
    }

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        currentStoreState = storeState
        val users = excludeUsersAlreadyInChannel(storeState.friendUsersMap, storeState.channel)
        updateViewState(
            ViewState(
                checkedUsers,
                GroupInviteFriendsSheetAdapter.FriendItem.createItems(users, checkedUsers),
                viewState?.showSearchIcon ?: true,
            ),
        )
    }

    private fun onSearch(searchText: CharSequence) {
        val users = currentStoreState.friendUsersMap.values.filter {
            it.username.contains(searchText, ignoreCase = true)
        }
        updateViewState(
            ViewState(
                checkedUsers,
                GroupInviteFriendsSheetAdapter.FriendItem.createItems(users, checkedUsers),
                searchText.isEmpty() && checkedUsers.isEmpty(),
            ),
        )
    }

    sealed class Event {
        data object ChannelFull : Event()
    }

    data class StoreState(
        val friendUsersMap: Map<Long, User> = emptyMap(),
        val channel: Channel? = null,
    )

    data class ViewState(
        val checkedUsers: HashSet<User>,
        val friendItems: List<GroupInviteFriendsSheetAdapter.FriendItem>,
        val showSearchIcon: Boolean = true,
    )

    companion object {
        const val NO_CHANNEL_ID = -1L
        private const val MAX_GROUP_MEMBERS = 10
        private const val MAX_GROUP_MEMBERS_STAFF = 25
        private const val SEARCH_DEBOUNCE_MILLIS = 150L

        internal fun excludeUsersAlreadyInChannel(
            friendUsersMap: Map<Long, User>,
            channel: Channel?,
        ): List<User> {
            val recipientIds = channel?.recipients?.mapTo(HashSet()) { it.id }
            return friendUsersMap.filterKeys { recipientIds?.contains(it) != true }.values.toList()
        }

        private fun observeFriends(
            storeUserRelationships: StoreUserRelationships,
            storeUser: StoreUser,
        ): Observable<Map<Long, User>> =
            storeUserRelationships.observeForType(ModelUserRelationship.TYPE_FRIEND)
                .switchMap { storeUser.observeUsers(it.keys) }

        internal fun observeStoreState(
            channelId: Long,
            storeUserRelationships: StoreUserRelationships,
            storeUser: StoreUser,
            storeChannels: StoreChannels,
        ): Observable<StoreState> {
            val friends = observeFriends(storeUserRelationships, storeUser)
            return if (channelId == NO_CHANNEL_ID) {
                friends.map { StoreState(it) }
            } else {
                Observable.combineLatest(storeChannels.observeChannel(channelId), friends) { channel, users ->
                    StoreState(users, channel)
                }
            }
        }
    }
}
