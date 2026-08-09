package com.discord.dialogs.useractions

import com.discord.utilities.rest.contextPropertiesOf
import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject

/**
 * Decides which of block / unblock / remove-friend the user-actions dialog offers, and performs the
 * chosen one.
 *
 * The three visibilities are mutually consistent by construction: a blocked user can only be
 * unblocked, anyone else can be blocked, and only an actual friend can be un-friended. That is one
 * relationship type driving three booleans, which is why they are computed here rather than in the
 * view.
 */
class UserActionsDialogViewModel(
    private val userId: Long,
    channelId: Long,
    storeObservable: Observable<StoreState> = observeStores(userId, channelId),
) : AppViewModel<UserActionsDialogViewModel.ViewState>(ViewState.Uninitialized) {

    /** Raw inputs, before the relationship type is turned into button visibilities. */
    data class StoreState(
        val user: User?,
        val relationshipType: Int?,
        val guildMember: GuildMember?,
        val friendNickname: String?,
        val isIgnored: Boolean,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()

        data class Loaded(
            val user: User,
            val userNickname: String?,
            val friendNickname: String?,
            val showBlockItem: Boolean,
            val showUnblockItem: Boolean,
            val showRemoveFriendItem: Boolean,
            val showIgnoreItem: Boolean,
            val showUnignoreItem: Boolean,
        ) : ViewState() {
            /** Only a friend can carry a friend nickname, so only they are offered one. */
            val showFriendNicknameItem: Boolean get() = showRemoveFriendItem
        }
    }

    /** One-shot outcomes; the dialog toasts and closes on either. */
    sealed class Event {
        /** [username] is set only for the toasts whose copy names the user. */
        data class Success(
            @StringRes val successMessageStringRes: Int,
            val username: String? = null,
        ) : Event()

        data class Failure(@StringRes val failureMessageStringRes: Int) : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()

    fun observeEvents(): Observable<Event> = eventSubject

    init {
        storeObservable
            .computationLatest()
            .ui(this)
            .appSubscribe(UserActionsDialogViewModel::class.java) { handleStoreState(it) }
    }

    private fun handleStoreState(storeState: StoreState) {
        val user = storeState.user
        if (user == null) {
            eventSubject.onNext(Event.Failure(R.string.default_failure_to_perform_action_message))
            return
        }
        val type = ModelUserRelationship.getType(storeState.relationshipType)
        updateViewState(
            ViewState.Loaded(
                user = user,
                userNickname = storeState.guildMember?.nick,
                friendNickname = storeState.friendNickname,
                showBlockItem = type != ModelUserRelationship.TYPE_BLOCKED,
                showUnblockItem = type == ModelUserRelationship.TYPE_BLOCKED,
                showRemoveFriendItem = type == ModelUserRelationship.TYPE_FRIEND,
                // Blocking already hides everything ignoring would, so the softer action is not
                // offered alongside it.
                showIgnoreItem = type != ModelUserRelationship.TYPE_BLOCKED && !storeState.isIgnored,
                showUnignoreItem = type != ModelUserRelationship.TYPE_BLOCKED && storeState.isIgnored,
            ),
        )
    }

    fun blockUser() {
        val api = RestAPI.apiOrNull ?: return emitGenericFailure()
        api.addRelationship(
            userId,
            RestAPIParams.UserRelationship(type = ModelUserRelationship.TYPE_BLOCKED),
            relationshipContext(LOCATION_USER_PROFILE),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = UserActionsDialogViewModel::class.java,
                errorHandler = { emitGenericFailure() },
            ) { eventSubject.onNext(Event.Success(R.string.user_has_been_blocked)) }
    }

    /** The sheet needs the id to render the avatar and name it is confirming against. */
    val userIdForConfirmation: Long get() = userId

    fun ignoreUser() {
        val api = RestAPI.apiOrNull ?: return emitGenericFailure()
        api.ignoreUser(userId, relationshipContext(LOCATION_USER_PROFILE))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = UserActionsDialogViewModel::class.java,
                errorHandler = { emitGenericFailure() },
            ) {
                StoreStream.setUserIgnored(userId, true)
                eventSubject.onNext(Event.Success(R.string.ignored_user_success_toast, displayName()))
            }
    }

    fun unignoreUser() {
        val api = RestAPI.apiOrNull ?: return emitGenericFailure()
        api.unignoreUser(userId, relationshipContext(LOCATION_USER_PROFILE))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = UserActionsDialogViewModel::class.java,
                errorHandler = { emitGenericFailure() },
            ) {
                StoreStream.setUserIgnored(userId, false)
                eventSubject.onNext(Event.Success(R.string.unignored_user_success_toast, displayName()))
            }
    }

    fun removeRelationship(@StringRes successMessageStringRes: Int) {
        val api = RestAPI.apiOrNull ?: return emitGenericFailure()
        api.removeRelationship(userId, relationshipContext(LOCATION_USER_PROFILE))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = UserActionsDialogViewModel::class.java,
                errorHandler = { emitGenericFailure() },
            ) { eventSubject.onNext(Event.Success(successMessageStringRes)) }
    }

    private fun displayName(): String? =
        (viewState as? ViewState.Loaded)?.let { it.friendNickname ?: it.user.username }

    private fun emitGenericFailure() {
        eventSubject.onNext(Event.Failure(R.string.default_failure_to_perform_action_message))
    }

    companion object {
        private const val LOCATION_USER_PROFILE = "User Profile"

        /**
         * The nickname comes from the *channel's* guild, so a DM (no guild) contributes none — the
         * dialog then falls back to the plain username.
         */
        private fun observeStores(userId: Long, channelId: Long): Observable<StoreState> {
            val guilds = StoreStream.getGuilds()
            val member: Observable<GuildMember?> = StoreStream.getChannels()
                .observeChannel(channelId)
                .switchMap { channel: Channel? ->
                    if (channel == null) {
                        Observable.just<GuildMember?>(null)
                    } else {
                        guilds.observeComputed(channel.guildId, setOf(userId)).map { it[userId] }
                    }
                }
                .distinctUntilChanged()

            return Observable.combineLatest(
                StoreStream.getUsers().observeUser(userId),
                StoreStream.getUserRelationships().observe(userId),
                member,
                StoreStream.getUserRelationships().observeNickname(userId),
                StoreStream.getUserRelationships().observeIgnoredUserIds().map { userId in it },
            ) { user, relationshipType, guildMember, friendNickname, isIgnored ->
                StoreState(user, relationshipType, guildMember, friendNickname, isIgnored)
            }
                .distinctUntilChanged()
        }

        /** `X-Context-Properties` is a base64 JSON blob naming where the action was taken. */
        private fun relationshipContext(location: String): String =
            contextPropertiesOf("location" to location)
    }
}
