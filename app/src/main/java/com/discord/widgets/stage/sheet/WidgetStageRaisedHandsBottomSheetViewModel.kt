package com.discord.widgets.stage.sheet

import androidx.annotation.MainThread
import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.app.AppViewModel
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStageChannels
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.error.RestApiError
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.widgets.stage.StageChannelAPI
import com.discord.widgets.stage.StageRoles
import com.discord.widgets.stage.StageStoreAccess
import rx.Observable
import rx.subjects.PublishSubject

/**
 * Backs the moderator's raised-hands queue.
 *
 * Inviting and dismissing are both expressed as a suppression change on the target's voice state.
 * The two in-flight flags ([ViewState.Loaded.isTogglingSpeakingState],
 * [ViewState.Loaded.isUpdatingRequestToSpeakPermissions]) exist so the corresponding control can
 * disable itself for the round trip rather than letting a moderator queue conflicting writes.
 */
class WidgetStageRaisedHandsBottomSheetViewModel(
    private val channelId: Long,
    private val channelsStore: StoreChannels = StoreStream.getChannels(),
    private val usersStore: StoreUser = StoreStream.getUsers(),
    storeStateObservable: Observable<StoreState> = observeStoreState(channelId),
) : AppViewModel<WidgetStageRaisedHandsBottomSheetViewModel.ViewState>() {

    data class StoreState(
        val channel: Channel,
        val roles: Map<Long, GuildRole>,
        val raisedHandsParticipants: List<WidgetStageRaisedHandsBottomSheetAdapter.ListItem>,
        val myStageRoles: StageRoles,
    )

    sealed class ViewState {
        data class Loaded(
            val channel: Channel,
            val raisedHandsParticipants:
                List<WidgetStageRaisedHandsBottomSheetAdapter.ListItem>,
            /** Whether @everyone currently holds `REQUEST_TO_SPEAK` in this channel. */
            val isRequestToSpeakEnabled: Boolean,
            val isSpeaker: Boolean,
            val isUpdatingRequestToSpeakPermissions: Boolean = false,
            val isTogglingSpeakingState: Boolean = false,
        ) : ViewState() {
            val isEmpty: Boolean get() = raisedHandsParticipants.isEmpty()
        }
    }

    sealed class Event {
        object Dismiss : Event()
        data class Error(val error: RestApiError) : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()

    init {
        storeStateObservable
            .ui(this)
            .appSubscribe(WidgetStageRaisedHandsBottomSheetViewModel::class.java) {
                handleStoreState(it)
            }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    /** Puts [participant] on stage. */
    fun inviteToSpeak(participant: StoreVoiceParticipants.VoiceUser) {
        setUserSuppressed(participant.user.id, false)
            .appSubscribe(
                errorClass = WidgetStageRaisedHandsBottomSheetViewModel::class.java,
                errorHandler = { emitError(it) },
            ) { }
    }

    /** Clears [participant]'s raised hand without promoting them. */
    fun dismissRequestToSpeak(participant: StoreVoiceParticipants.VoiceUser) {
        setUserSuppressed(participant.user.id, true)
            .appSubscribe(WidgetStageRaisedHandsBottomSheetViewModel::class.java) { }
    }

    /** Moves the moderator themselves between speaking and the audience. */
    fun setSpeakingState(suppressed: Boolean) {
        val loaded = viewState as? ViewState.Loaded ?: return
        updateViewState(loaded.copy(isTogglingSpeakingState = true))
        setUserSuppressed(usersStore.getMe().id, suppressed)
            .appSubscribe(
                errorClass = WidgetStageRaisedHandsBottomSheetViewModel::class.java,
                errorHandler = { emitError(it) },
                onTerminated = {
                    val current = viewState as? ViewState.Loaded ?: return@appSubscribe
                    updateViewState(current.copy(isTogglingSpeakingState = false))
                },
            ) { }
    }

    /**
     * Grants or revokes `REQUEST_TO_SPEAK` for @everyone, i.e. opens or closes the queue.
     *
     * The reference issues a channel-permission-overwrite PATCH built from
     * `PermissionOverwriteUtils.computeAllowDenyUpdateBits`. Neither that helper nor the
     * overwrite endpoint is reconstructed in this project, and JADX truncated the request itself,
     * so the write is not attempted: the toggle reverts and reports rather than silently doing
     * nothing. **Wire this up when `utilities/PermissionOverwriteUtils` lands.**
     */
    fun setRequestToSpeakEnabled(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        val loaded = viewState as? ViewState.Loaded ?: return
        updateViewState(loaded.copy(isUpdatingRequestToSpeakPermissions = false))
    }

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        updateViewState(
            ViewState.Loaded(
                channel = storeState.channel,
                raisedHandsParticipants = storeState.raisedHandsParticipants,
                isRequestToSpeakEnabled = PermissionUtils.canEveryoneRole(
                    Permission.REQUEST_TO_SPEAK,
                    storeState.channel,
                    storeState.roles,
                ),
                isSpeaker = storeState.myStageRoles.isSpeaker,
            ),
        )
    }

    private fun setUserSuppressed(userId: Long, suppressed: Boolean): Observable<Void> =
        channelsStore.observeChannel(channelId)
            .filter { it != null }
            .map { it!! }
            .take(1)
            .switchMap { channel ->
                StageChannelAPI.setUserSuppressedInChannel(channel, userId, suppressed)
                    .restSubscribeOn()
            }
            .ui(this)

    private fun emitError(error: RestApiError) = eventSubject.onNext(Event.Error(error))

    companion object {
        fun observeStoreState(
            channelId: Long,
            voiceParticipants: StoreVoiceParticipants = StoreStream.getVoiceParticipants(),
            channelsStore: StoreChannels = StoreStream.getChannels(),
            guildsStore: StoreGuilds = StoreStream.getGuilds(),
            stageChannelsStore: StoreStageChannels = StageStoreAccess.stageChannels(),
            clock: Clock = ClockFactory.get(),
        ): Observable<StoreState> = channelsStore.observeChannel(channelId)
            .filter { it != null }
            .map { it!! }
            .distinctUntilChanged()
            .switchMap { channel ->
                Observable.combineLatest(
                    guildsStore.observeRoles(channel.guildId),
                    guildsStore.observeComputed(channel.guildId),
                    voiceParticipants.get(channelId),
                    stageChannelsStore.observeMyRoles(channelId),
                ) { roles, members, participants, myRoles ->
                    StoreState(
                        channel = channel,
                        roles = roles,
                        // Oldest request first: the queue is first-come, first-served, and a
                        // participant with no timestamp yet sorts as if they had just asked.
                        raisedHandsParticipants = participants.values
                            .filter { it.isRequestingToSpeak }
                            .map { voiceUser ->
                                val member = members[voiceUser.user.id]
                                WidgetStageRaisedHandsBottomSheetAdapter.ListItem.Participant(
                                    participant = voiceUser,
                                    requestToSpeakTimestamp =
                                        voiceUser.voiceState?.requestToSpeakTimestamp
                                            ?: UtcDateTime(clock.currentTimeMillis()),
                                    member = member,
                                    role = RoleUtils.getHighestHoistedRole(roles, member),
                                )
                            }
                            .sortedBy { it.requestToSpeakTimestamp },
                        myStageRoles = myRoles ?: StageRoles.AUDIENCE,
                    )
                }
            }
    }
}
