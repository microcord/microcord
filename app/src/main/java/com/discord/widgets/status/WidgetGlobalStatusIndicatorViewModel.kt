package com.discord.widgets.status

import androidx.annotation.MainThread
import com.discord.api.channel.Channel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.stageinstance.StageInstance
import com.discord.api.voice.state.StageRequestToSpeakState
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreConnectivity
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStageChannels
import com.discord.stores.StoreStageInstances
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserRelationships
import com.discord.stores.StoreVoiceChannelSelected
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.voice.VoiceStoreAccess
import rx.Observable

/**
 * State for the app-wide status banner.
 *
 * The banner has exactly one job — tell the user the most important thing currently true about
 * their connection — and the two things that can be true are mutually exclusive, which is why
 * [StoreState] is a two-case hierarchy rather than one flat record. If the user is in a voice
 * channel, the banner describes that call. If they are not, it describes the gateway connection.
 * The voice branch wins because a user in a call already knows the app is online.
 */
class WidgetGlobalStatusIndicatorViewModel(
    storeStateObservable: Observable<StoreState> = observeStoreState(),
) : AppViewModel<WidgetGlobalStatusIndicatorViewModel.ViewState>(ViewState.Inactive) {

    /** Raw store output, before it is reduced to something renderable. */
    sealed class StoreState {

        /** No voice channel is selected, so the banner reports the gateway connection. */
        data class ConnectivityState(
            val connectivityState: StoreConnectivity.DelayedState,
        ) : StoreState()

        /** A voice channel is selected. */
        data class CallOngoing(
            val selectedVoiceChannel: Channel,
            val selectedTextChannel: Channel?,
            /**
             * The reference carries `RtcConnection.StateChange` here, which distinguishes
             * authenticating / awaiting-endpoint / no-route / connecting / connected. The
             * reconstruction's `RtcConnection` declares only `Metadata`, so the transport can
             * currently answer nothing finer than connected-or-not. See the handoff report.
             */
            val isRtcConnected: Boolean,
            val guild: Guild?,
            val participants: Map<Long, StoreVoiceParticipants.VoiceUser>,
            val requestToSpeakState: StageRequestToSpeakState,
            val blockedUsersOnStage: Int,
            val stageInstance: StageInstance?,
        ) : StoreState()
    }

    /** What the banner should render. */
    sealed class ViewState {

        /** Whether the local user is currently speaking on stage, which recolours the banner. */
        open val isSpeakingInOngoingCall: Boolean get() = false

        /** Nothing worth saying; the banner takes no space. */
        object Inactive : ViewState()

        /**
         * @param delay how long to wait before showing this. The store decides the delay so a
         *   two-second network blip never flashes a banner at the user.
         */
        data class Offline(val delay: Long, val airplaneMode: Boolean) : ViewState()

        data class Connecting(val delay: Long) : ViewState()

        data class CallOngoing(
            val selectedVoiceChannel: Channel,
            val selectedTextChannel: Channel?,
            val isRtcConnected: Boolean,
            val guild: Guild?,
            val participants: Int,
            val hasVideo: Boolean,
        ) : ViewState()

        data class StageChannelOngoing(
            val selectedVoiceChannel: Channel,
            val isRtcConnected: Boolean,
            val guild: Guild?,
            val isSpeaking: Boolean,
            val stageInstance: StageInstance?,
            val isInvitedToSpeak: Boolean = false,
            /** An accept/decline request is in flight; both buttons disable until it lands. */
            val isAckingInvitation: Boolean = false,
            val blockedUsersOnStage: Int = 0,
        ) : ViewState() {
            override val isSpeakingInOngoingCall: Boolean get() = isSpeaking
        }
    }

    init {
        storeStateObservable
            .distinctUntilChanged()
            .computationLatest()
            .ui(this)
            .appSubscribe(WidgetGlobalStatusIndicatorViewModel::class.java) { handleStoreState(it) }
    }

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        when (storeState) {
            is StoreState.ConnectivityState -> handleConnectivityState(storeState)
            is StoreState.CallOngoing -> handleCallOngoing(storeState)
        }
    }

    private fun handleConnectivityState(storeState: StoreState.ConnectivityState) {
        val delay = storeState.connectivityState.delay
        updateViewState(
            when (storeState.connectivityState.state) {
                StoreConnectivity.State.ONLINE -> ViewState.Inactive
                StoreConnectivity.State.OFFLINE -> ViewState.Offline(delay, airplaneMode = false)
                StoreConnectivity.State.OFFLINE_AIRPLANE_MODE ->
                    ViewState.Offline(delay, airplaneMode = true)
                StoreConnectivity.State.CONNECTING -> ViewState.Connecting(delay)
            },
        )
    }

    private fun handleCallOngoing(storeState: StoreState.CallOngoing) {
        if (storeState.selectedVoiceChannel.isStageVoiceChannel()) {
            handleStageChannelOngoing(storeState)
            return
        }
        updateViewState(
            ViewState.CallOngoing(
                selectedVoiceChannel = storeState.selectedVoiceChannel,
                selectedTextChannel = storeState.selectedTextChannel,
                isRtcConnected = storeState.isRtcConnected,
                guild = storeState.guild,
                participants = storeState.participants.size,
                hasVideo = storeState.participants.values.any { it.voiceState?.selfVideo == true },
            ),
        )
    }

    private fun handleStageChannelOngoing(storeState: StoreState.CallOngoing) {
        val isInvitedToSpeak =
            storeState.requestToSpeakState ==
                StageRequestToSpeakState.REQUESTED_TO_SPEAK_AND_AWAITING_USER_ACK
        val isSpeaking = storeState.requestToSpeakState == StageRequestToSpeakState.ON_STAGE

        // The reference plays SOUND_RECONNECT on the rising edge of the invitation so a user who is
        // not looking at the screen notices it. `utilities/media/AppSoundManager` has not been
        // reconstructed, so the invitation currently arrives silently — see the handoff report.

        updateViewState(
            ViewState.StageChannelOngoing(
                selectedVoiceChannel = storeState.selectedVoiceChannel,
                isRtcConnected = storeState.isRtcConnected,
                guild = storeState.guild,
                isSpeaking = isSpeaking,
                stageInstance = storeState.stageInstance,
                isInvitedToSpeak = isInvitedToSpeak,
                isAckingInvitation = false,
                blockedUsersOnStage = storeState.blockedUsersOnStage,
            ),
        )
    }

    /**
     * Accepts or declines an invitation to speak on stage.
     *
     * Accepting un-suppresses the user in the channel; declining suppresses them again. The buttons
     * disable optimistically for the round trip so a double tap cannot send two conflicting
     * updates, and re-enable whichever way the request resolves.
     */
    fun ackStageInvitationToSpeak(accept: Boolean) {
        val stageState = viewState as? ViewState.StageChannelOngoing ?: return
        val guildId = stageState.selectedVoiceChannel.guildId
        updateViewState(stageState.copy(isAckingInvitation = true))

        // The reference routes this through `StageChannelAPI.ackInvitationToSpeak`, which is being
        // reconstructed under `widgets/stage`. Delegate to it once it exists; the request itself is
        // the same PATCH either way.
        RestAPI.api.updateMyVoiceStates(
            guildId,
            RestAPIParams.ChannelVoiceStateUpdate(
                channelId = stageState.selectedVoiceChannel.id,
                suppress = !accept,
            ),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetGlobalStatusIndicatorViewModel::class.java,
                onTerminated = {
                    val current = viewState as? ViewState.StageChannelOngoing ?: return@appSubscribe
                    updateViewState(current.copy(isAckingInvitation = false))
                },
            ) { }
    }

    companion object {

        /**
         * @param blockedRelationships user id → relationship type, used to warn a user who is about
         *   to join a stage that people they have blocked are already speaking on it.
         */
        @Suppress("LongParameterList")
        fun observeStoreState(
            voiceChannelSelectedStore: StoreVoiceChannelSelected =
                StoreStream.getVoiceChannelSelected(),
            connectivityStore: StoreConnectivity = StoreStream.getConnectivity(),
            channelsSelectedStore: StoreChannelsSelected = StoreStream.getChannelsSelected(),
            guildsStore: StoreGuilds = StoreStream.getGuilds(),
            voiceParticipantsStore: StoreVoiceParticipants = StoreStream.getVoiceParticipants(),
            stageChannelsStore: StoreStageChannels = StoreStream.getStageChannels(),
            userRelationshipsStore: StoreUserRelationships = StoreStream.getUserRelationships(),
            stageInstancesStore: StoreStageInstances = StoreStream.getStageInstances(),
        ): Observable<StoreState> = voiceChannelSelectedStore.observeSelectedChannel()
            .switchMap { channel ->
                if (channel == null) {
                    connectivityStore.observeState().map(StoreState::ConnectivityState)
                } else {
                    Observable.combineLatest(
                        Observable.just(channel),
                        channelsSelectedStore.observeSelectedChannel(),
                        VoiceStoreAccess.observeRtcConnectionState(),
                        guildsStore.observeGuild(channel.guildId),
                        voiceParticipantsStore.get(channel.id),
                        stageChannelsStore.observeMyRequestToSpeakState(channel.id),
                        observeBlockedUsersOnStage(
                            channel.id,
                            stageChannelsStore,
                            userRelationshipsStore,
                        ),
                        stageInstancesStore.observeStageInstanceForChannel(channel.id),
                    ) { voiceChannel,
                        textChannel,
                        isRtcConnected,
                        guild,
                        participants,
                        requestToSpeakState,
                        blockedUsersOnStage,
                        stageInstance ->
                        StoreState.CallOngoing(
                            selectedVoiceChannel = voiceChannel,
                            selectedTextChannel = textChannel,
                            isRtcConnected = isRtcConnected,
                            guild = guild,
                            participants = participants,
                            requestToSpeakState = requestToSpeakState,
                            blockedUsersOnStage = blockedUsersOnStage,
                            stageInstance = stageInstance,
                        )
                    }
                }
            }

        /**
         * How many users the local user has blocked are currently holding a role on this stage.
         *
         * JADX decompiles the reference's filter to a constant `false` (`? false : false`), so the
         * predicate itself is not recoverable from that source; the surrounding code — a count of
         * relationships intersected with the channel's stage roles, rendered into the
         * `stage_speak_invite_blocked_users` warning — makes the intent unambiguous.
         */
        private fun observeBlockedUsersOnStage(
            channelId: Long,
            stageChannelsStore: StoreStageChannels,
            userRelationshipsStore: StoreUserRelationships,
        ): Observable<Int> = Observable.combineLatest(
            stageChannelsStore.observeStageRolesByChannel(channelId),
            userRelationshipsStore.observeForType(ModelUserRelationship.TYPE_BLOCKED),
        ) { stageRoles, blockedUsers ->
            if (stageRoles == null) 0 else blockedUsers.keys.count(stageRoles::containsKey)
        }
    }
}
