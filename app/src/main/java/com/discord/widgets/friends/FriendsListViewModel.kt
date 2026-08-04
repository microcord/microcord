package com.discord.widgets.friends

import android.util.Base64
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.channel.isDM
import com.discord.api.presence.ClientStatus
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelApplicationStream
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.friendsuggestions.FriendSuggestion
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreChannels
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserRelationships
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.error.RestApiError
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rest.RestAPIAbortMessages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils
import java.util.concurrent.TimeUnit
import rx.Emitter
import rx.Observable
import rx.Subscription
import rx.functions.Cancellable
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject

/**
 * State and actions for the Friends tab.
 *
 * Reconstruction of the reference `com.discord.widgets.friends.FriendsListViewModel`.
 *
 * Two things about the shape are load-bearing and are preserved verbatim:
 *
 * 1. **Row building runs off the main thread.** A user with thousands of relationships would stall
 *    a frame otherwise, so [handleStoreState] cancels any in-flight build and starts a new one on
 *    the computation scheduler ([asyncComputeAndHandleOnUiThread]). Only the finished
 *    [ListSections] crosses back to the main thread.
 * 2. **Expansion state lives here, not in the row list.** The "See All" toggles mutate
 *    [isPendingSectionExpanded] / [isSuggestedSectionExpanded] and re-derive the visible rows from
 *    the already-computed [ListSections], so expanding never re-hits the stores.
 *
 * Every action reports its outcome through [observeEvents]; nothing in this class toasts or
 * navigates by itself.
 */
class FriendsListViewModel(
    val storeObservable: Observable<StoreState> = observeStores(),
    val storeChannels: StoreChannels = StoreStream.getChannels(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
) : AppViewModel<FriendsListViewModel.ViewState>(ViewState.Uninitialized) {

    /** Raw store snapshot; no ordering or sectioning has happened yet. */
    data class StoreState(
        val showContactSyncIcon: Boolean,
        val showContactSyncUpsell: Boolean,
        val channelId: Long,
        val relationshipsState: StoreUserRelationships.UserRelationshipsState,
        val users: Map<Long, User>,
        val presences: Map<Long, Presence>,
        val applicationStreams: Map<Long, ModelApplicationStream>,
        val friendSuggestions: Map<Long, FriendSuggestion>,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()

        data class Empty(val showContactSyncIcon: Boolean) : ViewState()

        data class Loaded(val showContactSyncIcon: Boolean, val items: List<Item>) : ViewState()
    }

    /** One-shot outcomes. The fragment owns every toast and call launch. */
    sealed class Event {
        data class ShowToast(@StringRes val stringRes: Int) : Event()

        data class ShowFriendRequestErrorToast(val abortCode: Int, val username: String) : Event()

        data class LaunchVoiceCall(val channelId: Long) : Event()
    }

    /**
     * A row. [type] is the recycler view type, so the constants below and the branches in
     * `WidgetFriendsListAdapter.onCreateViewHolder` must stay in step.
     */
    sealed class Item(override val type: Int) : MGRecyclerDataPayload {

        data class Friend(
            val user: User,
            val presence: Presence?,
            val isApplicationStreaming: Boolean,
        ) : Item(TYPE_FRIEND) {
            override val key: String = "$type${user.id}"

            /** Anything but offline/invisible counts as online for sectioning. */
            fun isOnline(): Boolean = presence != null &&
                presence.status in setOf(ClientStatus.ONLINE, ClientStatus.IDLE, ClientStatus.DND)
        }

        data class PendingFriendRequest(
            val user: User,
            val presence: Presence?,
            val relationshipType: Int,
        ) : Item(TYPE_PENDING_FRIEND) {
            override val key: String = "$type${user.id}"
        }

        data class PendingHeader(
            @StringRes val titleStringResId: Int,
            val count: Int,
            val isPendingSectionExpanded: Boolean,
            val showExpandButton: Boolean,
        ) : Item(TYPE_PENDING_HEADER) {
            override val key: String = type.toString()
        }

        data class Header(
            @StringRes val titleStringResId: Int,
            val count: Int,
        ) : Item(TYPE_HEADER) {
            override val key: String = "$type$titleStringResId"
        }

        data class SuggestedFriendsHeader(
            val count: Int,
            val isExpanded: Boolean,
            val showExpandButton: Boolean,
        ) : Item(TYPE_SUGGESTED_FRIEND_HEADER) {
            override val key: String = type.toString()
        }

        data class SuggestedFriend(val suggestion: FriendSuggestion) : Item(TYPE_SUGGESTED_FRIEND) {
            override val key: String = "$type -- ${suggestion.user.id}"
        }

        data class ContactSyncUpsell(val dismissed: Boolean) : Item(TYPE_CONTACT_SYNC_UPSELL) {
            override val key: String = type.toString()
        }

        companion object {
            const val TYPE_FRIEND = 0
            const val TYPE_PENDING_FRIEND = 1
            const val TYPE_PENDING_HEADER = 2
            const val TYPE_HEADER = 3
            const val TYPE_SUGGESTED_FRIEND_HEADER = 4
            const val TYPE_SUGGESTED_FRIEND = 5
            const val TYPE_CONTACT_SYNC_UPSELL = 6
        }
    }

    /**
     * The fully computed list, still sectioned. Kept whole so that toggling a section only has to
     * re-run [getVisibleItems], never the store fold.
     */
    data class ListSections(
        val suggestionsHeaderItem: Item.SuggestedFriendsHeader?,
        val suggestedFriendItems: List<Item.SuggestedFriend>,
        val pendingHeaderItem: Item.PendingHeader?,
        val pendingItems: List<Item>,
        val friendsItemsWithHeaders: List<Item>,
        val contactSyncUpsell: Item.ContactSyncUpsell?,
    )

    private val eventSubject = PublishSubject.create<Event>()

    private var listSections = ListSections(null, emptyList(), null, emptyList(), emptyList(), null)
    private var computeItemJob: Cancellable? = null
    private var isPendingSectionExpanded = false
    private var isSuggestedSectionExpanded = false

    init {
        storeObservable
            .ui(this)
            .appSubscribe(FriendsListViewModel::class.java) { handleStoreState(it) }
    }

    @MainThread
    fun observeEvents(): Observable<Event> = eventSubject

    // region actions

    fun acceptFriendRequest(userId: Long, username: String) {
        val api = restApi() ?: return
        api.addRelationship(userId, RestAPIParams.UserRelationship(), relationshipContext(LOCATION))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = FriendsListViewModel::class.java,
                errorHandler = { error ->
                    RestAPIAbortMessages.handleAbortCodeOrDefault(
                        error,
                        onHandle = { emitShowFriendRequestAbortToast(error.code, username) },
                        onDefault = {
                            emitShowToastEvent(R.string.default_failure_to_perform_action_message)
                            false
                        },
                    )
                },
            ) { emitShowToastEvent(R.string.accept_request_button_after) }
    }

    /**
     * @param relationshipType the type the row had *before* the removal, which is what decides
     *   between "ignored" (an incoming request) and "cancelled" (an outgoing one).
     */
    fun removeFriendRequest(userId: Long, relationshipType: Int) {
        val api = restApi() ?: return
        val successMessageStringRes =
            if (relationshipType == ModelUserRelationship.TYPE_INVITE_INCOMING) {
                R.string.friend_request_ignored
            } else {
                R.string.friend_request_cancelled
            }
        api.removeRelationship(userId, relationshipContext(LOCATION))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = FriendsListViewModel::class.java,
                errorHandler = { emitShowToastEvent(R.string.default_failure_to_perform_action_message) },
            ) { emitShowToastEvent(successMessageStringRes) }
    }

    fun acceptFriendSuggestion(username: String, discriminator: Int) {
        val api = restApi() ?: return
        api.sendRelationshipRequest(
            RestAPIParams.UserRelationship.Add(
                username = username,
                discriminator = discriminator,
            ),
            relationshipContext(LOCATION_FRIEND_SUGGESTION),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = FriendsListViewModel::class.java,
                errorHandler = { error ->
                    RestAPIAbortMessages.handleAbortCodeOrDefault(error, onHandle = {
                        emitShowFriendRequestAbortToast(error.code, username)
                    })
                },
            ) { /* the relationship dispatch updates the list */ }
    }

    fun ignoreSuggestion(userId: Long) {
        val api = restApi() ?: return
        api.ignoreFriendSuggestion(userId)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(FriendsListViewModel::class.java) { /* suggestion removal is dispatched */ }
    }

    fun launchVoiceCall(userId: Long) {
        val api = restApi() ?: return
        createOrFetchDM(api, userId)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = FriendsListViewModel::class.java,
                errorHandler = { emitShowToastEvent(R.string.default_failure_to_perform_action_message) },
            ) { channelId -> emitLaunchVoiceCallEvent(channelId) }
    }

    @MainThread
    fun dismissContactSyncUpsell() {
        StoreStream.getContactSync().dismissFriendsListUpsell()
    }

    @MainThread
    fun handleClickPendingHeader() {
        isPendingSectionExpanded = !isPendingSectionExpanded
        generateLoadedItems(currentShowContactSyncIcon())
    }

    @MainThread
    fun handleClickSuggestedHeader() {
        isSuggestedSectionExpanded = !isSuggestedSectionExpanded
        generateLoadedItems(currentShowContactSyncIcon())
    }

    // endregion

    // region state

    private fun handleStoreState(storeState: StoreState) {
        computeItemJob?.cancel()
        val relationshipsState = storeState.relationshipsState
        if (relationshipsState !is StoreUserRelationships.UserRelationshipsState.Loaded) {
            updateViewState(ViewState.Uninitialized)
            return
        }
        val relationships = relationshipsState.relationships
        if (relationships.isEmpty()) {
            updateViewState(
                ViewState.Empty(storeState.showContactSyncIcon || storeState.showContactSyncUpsell),
            )
            return
        }
        computeItemJob = asyncComputeAndHandleOnUiThread(
            compute = {
                buildListSections(
                    relationships = relationships,
                    users = storeState.users,
                    presences = storeState.presences,
                    applicationStreams = storeState.applicationStreams,
                    showContactSyncUpsell = storeState.showContactSyncUpsell,
                    friendSuggestions = storeState.friendSuggestions,
                )
            },
            onSuccess = { sections -> handleComputedItems(sections, storeState.showContactSyncIcon) },
        )
    }

    @MainThread
    private fun handleComputedItems(listSections: ListSections, showContactSyncIcon: Boolean) {
        this.listSections = listSections
        generateLoadedItems(showContactSyncIcon)
    }

    @MainThread
    private fun generateLoadedItems(showContactSyncIcon: Boolean) {
        listSections = listSections.copy(
            suggestionsHeaderItem = listSections.suggestionsHeaderItem
                ?.copy(isExpanded = isSuggestedSectionExpanded),
            pendingHeaderItem = listSections.pendingHeaderItem
                ?.copy(isPendingSectionExpanded = isPendingSectionExpanded),
        )
        val visibleItems = getVisibleItems(listSections)
        updateViewState(
            if (visibleItems.isNotEmpty()) {
                ViewState.Loaded(showContactSyncIcon, visibleItems)
            } else {
                ViewState.Empty(showContactSyncIcon)
            },
        )
    }

    private fun currentShowContactSyncIcon(): Boolean = when (val state = viewState) {
        is ViewState.Empty -> state.showContactSyncIcon
        is ViewState.Loaded -> state.showContactSyncIcon
        else -> false
    }

    /**
     * Applies the two collapse toggles to an already-computed [ListSections].
     *
     * The reference keeps this private and reads the flags off `this`; here the flags are explicit
     * parameters on the companion so the rule is testable without standing up a view model (whose
     * constructor subscribes to the stores on the Android main thread).
     */
    private fun getVisibleItems(listSections: ListSections): List<Item> = Companion.getVisibleItems(
        listSections,
        isSuggestedSectionExpanded = isSuggestedSectionExpanded,
        isPendingSectionExpanded = isPendingSectionExpanded,
    )


    /**
     * Runs [compute] on the computation scheduler and delivers the result on the main thread.
     * The returned [Cancellable] unsubscribes a build that has been superseded, so a burst of store
     * updates cannot deliver stale rows after fresh ones.
     */
    private fun <T> asyncComputeAndHandleOnUiThread(
        compute: () -> T,
        onError: ((RestApiError) -> Unit)? = null,
        onSuccess: (T) -> Unit,
    ): Cancellable {
        var subscription: Subscription? = null
        Observable
            .create<T>({ emitter -> emitter.onNext(compute()) }, Emitter.BackpressureMode.NONE)
            .subscribeOn(Schedulers.computation())
            .ui(this)
            .appSubscribe(
                errorClass = FriendsListViewModel::class.java,
                subscriptionHandler = { subscription = it },
                errorHandler = onError,
                onNext = onSuccess,
            )
        return Cancellable { subscription?.unsubscribe() }
    }

    // endregion

    // region events

    private fun emitShowToastEvent(@StringRes stringRes: Int) {
        eventSubject.onNext(Event.ShowToast(stringRes))
    }

    private fun emitShowFriendRequestAbortToast(abortCode: Int, username: String) {
        eventSubject.onNext(Event.ShowFriendRequestErrorToast(abortCode, username))
    }

    private fun emitLaunchVoiceCallEvent(channelId: Long) {
        eventSubject.onNext(Event.LaunchVoiceCall(channelId))
    }

    // endregion

    /**
     * Resolves the DM channel for [userId], reusing the existing one when the store already knows
     * about it.
     *
     * The reference calls `RestAPI.createOrFetchDM`, which is not part of this project's REST
     * façade — `RestAPI` here only publishes the Retrofit interface. The lookup is therefore done
     * against [storeChannels] first and falls back to `POST users/@me/channels`, which is exactly
     * what the reference helper does.
     */
    private fun createOrFetchDM(api: RestAPIInterface, userId: Long): Observable<Long> {
        val existing = storeChannels.getAllChannels().values.firstOrNull { channel ->
            channel.isDM() && channel.recipients?.singleOrNull()?.id == userId
        }
        return if (existing != null) {
            Observable.just(existing.id)
        } else {
            api.userCreateChannel(RestAPIParams.CreateChannel(listOf(userId))).map { it.id }
        }
    }

    companion object {
        private const val COLLAPSED_ITEM_COUNT = 2
        private const val LOCATION = "Friends List"
        private const val LOCATION_FRIEND_SUGGESTION = "Friends List - Friend Suggestion"

        private const val CONTACTS_CONNECTION_TYPE = "contacts"

        /** `X-Context-Properties` is a base64 JSON blob naming where the action was taken. */
        private fun relationshipContext(location: String): String = Base64.encodeToString(
            "{\"location\":\"$location\"}".toByteArray(),
            Base64.NO_WRAP,
        )

        /**
         * Folds the raw store maps into sections. Pure — it is deliberately callable from a background
         * thread and from tests.
         *
         * Ordering rules, all taken from the reference:
         * - suggestions by user id;
         * - pending requests: incoming before outgoing, then by display name;
         * - friends split online/offline, each by display name.
         */
        internal fun buildListSections(
            relationships: Map<Long, Int>,
            users: Map<Long, User>,
            presences: Map<Long, Presence>,
            applicationStreams: Map<Long, ModelApplicationStream>,
            showContactSyncUpsell: Boolean,
            friendSuggestions: Map<Long, FriendSuggestion>,
        ): ListSections {
            val suggestedFriendItems = friendSuggestions.values
                .map(Item::SuggestedFriend)
                .sortedBy { it.suggestion.user.id }

            val suggestionsHeaderItem = if (suggestedFriendItems.isNotEmpty()) {
                Item.SuggestedFriendsHeader(
                    count = suggestedFriendItems.size,
                    isExpanded = false,
                    showExpandButton = suggestedFriendItems.size > COLLAPSED_ITEM_COUNT,
                )
            } else {
                null
            }

            val pending = ArrayList<Item.PendingFriendRequest>()
            val friends = ArrayList<Item.Friend>()
            for ((userId, rawType) in relationships) {
                val user = users[userId] ?: continue
                val type = ModelUserRelationship.getType(rawType)
                if (type == ModelUserRelationship.TYPE_BLOCKED) continue
                val presence = presences[userId]
                if (type == ModelUserRelationship.TYPE_INVITE_INCOMING ||
                    type == ModelUserRelationship.TYPE_INVITE_OUTGOING
                ) {
                    pending.add(Item.PendingFriendRequest(user, presence, type))
                } else {
                    friends.add(Item.Friend(user, presence, applicationStreams.containsKey(userId)))
                }
            }

            val pendingItems: List<Item> = pending.sortedWith(Comparator { first, second ->
                when {
                    first.relationshipType == ModelUserRelationship.TYPE_INVITE_INCOMING &&
                        second.relationshipType == ModelUserRelationship.TYPE_INVITE_OUTGOING -> -1
                    first.relationshipType == ModelUserRelationship.TYPE_INVITE_OUTGOING &&
                        second.relationshipType == ModelUserRelationship.TYPE_INVITE_INCOMING -> 1
                    else -> UserUtils.compareUserNames(first.user, second.user)
                }
            })

            val pendingHeaderItem = if (pendingItems.isNotEmpty()) {
                Item.PendingHeader(
                    titleStringResId = R.string.friends_pending_request_header,
                    count = pendingItems.size,
                    isPendingSectionExpanded = false,
                    showExpandButton = pendingItems.size > COLLAPSED_ITEM_COUNT,
                )
            } else {
                null
            }

            val byName = Comparator<Item.Friend> { first, second ->
                UserUtils.compareUserNames(first.user, second.user)
            }
            val friendsItemsWithHeaders = ArrayList<Item>()
            val onlineFriendItems = friends.filter(Item.Friend::isOnline).sortedWith(byName)
            if (onlineFriendItems.isNotEmpty()) {
                friendsItemsWithHeaders.add(
                    Item.Header(R.string.friends_online_header, onlineFriendItems.size),
                )
                friendsItemsWithHeaders.addAll(onlineFriendItems)
            }
            val offlineFriendItems = friends.filterNot(Item.Friend::isOnline).sortedWith(byName)
            if (offlineFriendItems.isNotEmpty()) {
                friendsItemsWithHeaders.add(
                    Item.Header(R.string.friends_offline_header, offlineFriendItems.size),
                )
                friendsItemsWithHeaders.addAll(offlineFriendItems)
            }

            return ListSections(
                suggestionsHeaderItem = suggestionsHeaderItem,
                suggestedFriendItems = suggestedFriendItems,
                pendingHeaderItem = pendingHeaderItem,
                pendingItems = pendingItems,
                friendsItemsWithHeaders = friendsItemsWithHeaders,
                contactSyncUpsell = if (showContactSyncUpsell) Item.ContactSyncUpsell(false) else null,
            )
        }

        /** A section header is dropped when its body would be empty. */
        internal fun getVisibleItems(
            listSections: ListSections,
            isSuggestedSectionExpanded: Boolean,
            isPendingSectionExpanded: Boolean,
        ): List<Item> {
            val items = ArrayList<Item>()
            listSections.contactSyncUpsell?.let(items::add)

            val suggested = if (isSuggestedSectionExpanded) {
                listSections.suggestedFriendItems
            } else {
                listSections.suggestedFriendItems.take(COLLAPSED_ITEM_COUNT)
            }
            if (listSections.suggestionsHeaderItem != null && suggested.isNotEmpty()) {
                items.add(listSections.suggestionsHeaderItem)
                items.addAll(suggested)
            }

            val pending = if (isPendingSectionExpanded) {
                listSections.pendingItems
            } else {
                listSections.pendingItems.take(COLLAPSED_ITEM_COUNT)
            }
            if (listSections.pendingHeaderItem != null && pending.isNotEmpty()) {
                items.add(listSections.pendingHeaderItem)
                items.addAll(pending)
            }

            items.addAll(listSections.friendsItemsWithHeaders)
            return items
        }

        /**
         * One throttled stream over every store the list reads.
         *
         * The 1s leading-edge throttle is the reference's: presence updates arrive in bursts on a
         * large friend list and re-folding on each one is wasted work.
         */
        fun observeStores(): Observable<StoreState> {
            val channelsSelected = StoreStream.getChannelsSelected()
            val presences = StoreStream.getPresences()
            val users = StoreStream.getUsers()
            val userRelationships = StoreStream.getUserRelationships()
            val userConnections = StoreStream.getUserConnections()
            val applicationStreaming = StoreStream.getApplicationStreaming()
            val contactSync = StoreStream.getContactSync()
            val friendSuggestions = StoreStream.getFriendSuggestions()

            return ObservationDeckProvider.get()
                .connectRx(
                    channelsSelected,
                    presences,
                    users,
                    userRelationships,
                    applicationStreaming,
                    userConnections,
                    contactSync,
                )
                .leadingEdgeThrottle(1L, TimeUnit.SECONDS)
                .map {
                    val connectedAccounts = userConnections.getConnectedAccounts().connectedAccounts
                    val hasContactsConnection = connectedAccounts.any {
                        it.type == CONTACTS_CONNECTION_TYPE
                    }
                    val hasSyncingContactsConnection = connectedAccounts.any {
                        it.type == CONTACTS_CONNECTION_TYPE && it.friendSync
                    }
                    val showUpsell = !contactSync.getFriendsListUpsellDismissed() &&
                        !hasSyncingContactsConnection
                    val showIcon = !hasContactsConnection

                    StoreState(
                        showContactSyncIcon = showIcon && !showUpsell,
                        showContactSyncUpsell = showUpsell,
                        channelId = channelsSelected.getId(),
                        relationshipsState = userRelationships.getRelationshipsState(),
                        users = users.getUsers(),
                        presences = presences.getPresences(),
                        applicationStreams = applicationStreaming.getStreamsByUser(),
                        friendSuggestions = friendSuggestions.getFriendSuggestions(),
                    )
                }
        }
    }
}
