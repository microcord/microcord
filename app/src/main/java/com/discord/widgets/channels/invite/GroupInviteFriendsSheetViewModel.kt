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
import com.discord.widgets.channels.WidgetGroupInviteFriends
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
    private val submitter: (Channel?, List<User>) -> Observable<Channel> = { channel, users ->
        WidgetGroupInviteFriends.submit(channel, users)
    },
) : AppViewModel<GroupInviteFriendsSheetViewModel.ViewState>(ViewState()) {

    private val checkedUserIds = LinkedHashSet<Long>()
    private var currentStoreState = StoreState()
    private var searchText = ""
    private var restored = false
    private val eventSubject = PublishSubject.create<Event>()
    private val searchTextChangedPublisher = BehaviorSubject.create<CharSequence>("")

    init {
        storeStateObservable.ui(this).appSubscribe(
            javaClass,
            errorHandler = { updateViewState(requireViewState().copy(status = Status.ERROR)) },
            onNext = ::handleStoreState,
        )
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
        val state = requireViewState()
        if (isChecked) {
            if (checkedUserIds.size < availableSelectionCount(maxNumMembers, currentStoreState.channel)) {
                checkedUserIds.add(user.id)
            } else {
                eventSubject.onNext(Event.ChannelFull)
            }
        } else {
            checkedUserIds.remove(user.id)
        }
        updateViewState(buildViewState(state.status.takeUnless { it == Status.ERROR } ?: Status.READY))
    }

    fun restoreState(selectedUserIds: LongArray?, restoredSearchText: String?) {
        if (restored) return
        restored = true
        selectedUserIds?.forEach(checkedUserIds::add)
        searchText = restoredSearchText.orEmpty()
        searchTextChangedPublisher.onNext(searchText)
        handleStoreState(currentStoreState)
    }

    fun selectedUserIds(): LongArray = checkedUserIds.toLongArray()

    fun currentSearchText(): String = searchText

    fun submit() {
        val state = requireViewState()
        if (!state.canSubmit || state.status == Status.SUBMITTING) return
        val users = eligibleUsers(currentStoreState.friendUsersMap, currentStoreState.channel)
            .filter { it.id in checkedUserIds }
        if (users.isEmpty()) return
        updateViewState(state.copy(status = Status.SUBMITTING))
        submitter(currentStoreState.channel, users)
            .ui(this)
            .appSubscribe(
                javaClass,
                errorHandler = { updateViewState(buildViewState(Status.ERROR)) },
                onNext = { eventSubject.onNext(Event.Completed(it)) },
            )
    }

    fun retry() {
        if (requireViewState().status == Status.ERROR && checkedUserIds.isNotEmpty()) submit()
        else updateViewState(buildViewState())
    }

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        currentStoreState = storeState
        if (!storeState.isLoaded || channelId != NO_CHANNEL_ID && storeState.channel == null) {
            updateViewState(requireViewState().copy(status = Status.LOADING))
            return
        }
        val eligibleIds = eligibleUsers(storeState.friendUsersMap, storeState.channel).mapTo(HashSet()) { it.id }
        checkedUserIds.retainAll(eligibleIds)
        while (checkedUserIds.size > availableSelectionCount(maxNumMembers, storeState.channel)) {
            checkedUserIds.remove(checkedUserIds.last())
        }
        if (requireViewState().status != Status.SUBMITTING) updateViewState(buildViewState())
    }

    private fun onSearch(searchText: CharSequence) {
        this.searchText = searchText.toString()
        if (currentStoreState.isLoaded && requireViewState().status != Status.SUBMITTING) {
            updateViewState(buildViewState())
        }
    }

    private fun buildViewState(status: Status? = null): ViewState {
        val users = eligibleUsers(currentStoreState.friendUsersMap, currentStoreState.channel)
            .filter { matchesSearch(it, searchText) }
        val checkedUsers = currentStoreState.friendUsersMap.values
            .filterTo(LinkedHashSet()) { it.id in checkedUserIds }
        val resolvedStatus = status ?: when {
            availableSelectionCount(maxNumMembers, currentStoreState.channel) == 0 -> Status.FULL
            users.isEmpty() -> Status.EMPTY
            else -> Status.READY
        }
        return ViewState(
            checkedUsers = checkedUsers,
            friendItems = GroupInviteFriendsSheetAdapter.FriendItem.createItems(users, checkedUserIds),
            showSearchIcon = searchText.isEmpty() && checkedUsers.isEmpty(),
            status = resolvedStatus,
        )
    }

    sealed class Event {
        data object ChannelFull : Event()
        data class Completed(val channel: Channel) : Event()
    }

    data class StoreState(
        val friendUsersMap: Map<Long, User> = emptyMap(),
        val channel: Channel? = null,
        val isLoaded: Boolean = false,
    )

    data class ViewState(
        val checkedUsers: Set<User> = emptySet(),
        val friendItems: List<GroupInviteFriendsSheetAdapter.FriendItem> = emptyList(),
        val showSearchIcon: Boolean = true,
        val status: Status = Status.LOADING,
    ) {
        val canSubmit: Boolean
            get() = checkedUsers.isNotEmpty() &&
                (status == Status.READY || status == Status.EMPTY || status == Status.ERROR)
    }

    enum class Status { LOADING, READY, EMPTY, FULL, SUBMITTING, ERROR }

    companion object {
        const val NO_CHANNEL_ID = -1L
        private const val MAX_GROUP_MEMBERS = 10
        private const val MAX_GROUP_MEMBERS_STAFF = 25
        private const val SEARCH_DEBOUNCE_MILLIS = 150L

        internal fun eligibleUsers(
            friendUsersMap: Map<Long, User>,
            channel: Channel?,
        ): List<User> {
            val recipientIds = channel?.recipients?.mapTo(HashSet()) { it.id }
            return friendUsersMap.filterKeys { recipientIds?.contains(it) != true }
                .values
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, User::username).thenBy(User::id))
        }

        internal fun availableSelectionCount(maxNumMembers: Int, channel: Channel?): Int =
            (maxNumMembers - 1 - channel?.recipients.orEmpty().size).coerceAtLeast(0)

        internal fun matchesSearch(user: User, searchText: String): Boolean =
            user.username.contains(searchText.trim(), ignoreCase = true)

        private fun observeFriends(
            storeUserRelationships: StoreUserRelationships,
            storeUser: StoreUser,
        ): Observable<StoreState> = storeUserRelationships.observeUserRelationshipsState().switchMap { state ->
            when (state) {
                StoreUserRelationships.UserRelationshipsState.Unloaded -> Observable.just(StoreState())
                is StoreUserRelationships.UserRelationshipsState.Loaded -> {
                    val friends = state.relationships.filterValues { it == ModelUserRelationship.TYPE_FRIEND }
                    storeUser.observeUsers(friends.keys).map { StoreState(it, isLoaded = true) }
                }
            }
        }

        internal fun observeStoreState(
            channelId: Long,
            storeUserRelationships: StoreUserRelationships,
            storeUser: StoreUser,
            storeChannels: StoreChannels,
        ): Observable<StoreState> {
            val friends = observeFriends(storeUserRelationships, storeUser)
            return if (channelId == NO_CHANNEL_ID) {
                friends
            } else {
                Observable.combineLatest(storeChannels.observeChannel(channelId), friends) { channel, state ->
                    state.copy(channel = channel)
                }
            }
        }
    }
}
