package com.discord.widgets.voice.sheet

import androidx.annotation.MainThread
import com.discord.api.channel.Channel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.permission.Permission
import com.discord.utilities.permissions.PermissionUtils
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.stores.StoreMediaSettings
import com.discord.stores.StoreStageChannels
import com.discord.stores.StoreStream
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.widgets.stage.StageRoles
import com.discord.widgets.voice.model.CallModel
import rx.Observable

/**
 * Drives [WidgetVoiceSettingsBottomSheet] — the overflow menu behind the voice sheet's gear icon.
 *
 * Every row is conditional: invites only where the channel can be invited to, the participant
 * toggle only once there is video worth hiding, stage settings only for a stage moderator, and
 * reporting only on a stage.
 */
class WidgetVoiceSettingsBottomSheetViewModel(
    val channelId: Long,
    private val stageChannelsStore: StoreStageChannels = StoreStream.getStageChannels(),
    private val mediaSettingsStore: StoreMediaSettings = StoreStream.getMediaSettings(),
    val storeObservable: Observable<StoreState> = observeStoreState(channelId, stageChannelsStore),
) : AppViewModel<WidgetVoiceSettingsBottomSheetViewModel.ViewState>() {

    init {
        storeObservable
            .computationLatest()
            .ui(this)
            .subscribe(::handleStoreState)
    }

    sealed class StoreState {
        object Invalid : StoreState()

        data class Valid(
            val callModel: CallModel,
            val myStageRoles: StageRoles?,
        ) : StoreState()
    }

    data class ViewState(
        override val channel: Channel,
        override val showVoiceParticipants: Boolean,
        override val showChannelSettings: Boolean,
        override val showActivitiesItem: Boolean,
        override val members: List<VoiceMember>,
        override val isDeafened: Boolean,
        override val showSelfVideo: Boolean,
        override val showVoiceParticipantsToggle: Boolean,
        override val showInviteItem: Boolean,
        override val noiseProcessing: StoreMediaSettings.NoiseProcessing,
        override val showStageSettings: Boolean,
        override val showReportItem: Boolean,
        override val guild: Guild?,
    ) : WidgetVoiceSettingsBottomSheet.ViewState

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        updateViewState(toViewState(storeState) ?: return)
    }

    @MainThread
    fun onToggleNoiseCancellation() {
        mediaSettingsStore.toggleNoiseCancellation()
    }

    @MainThread
    fun onToggleSelfVideoHidden() {
        val currentViewState = viewState ?: return
        mediaSettingsStore.updateSelfVideoHidden(currentViewState.showSelfVideo)
    }

    fun onSelectNoiseProcessing(value: StoreMediaSettings.NoiseProcessing) {
        mediaSettingsStore.setNoiseProcessing(value)
    }

    fun onToggleDeafen() {
        mediaSettingsStore.toggleSelfDeafened()
    }

    fun onToggleVoiceParticipantsHidden() {
        // The current state *is* the new hidden flag: the switch shows "show participants", so
        // toggling it off is what hides them.
        val currentViewState = viewState ?: return
        mediaSettingsStore.updateVoiceParticipantsHidden(currentViewState.showVoiceParticipants)
    }

    companion object {
        internal fun toViewState(storeState: StoreState): ViewState? {
            if (storeState !is StoreState.Valid) return null
            val model = storeState.callModel
            val isStage = model.channel.isStageVoiceChannel()
            return ViewState(
                channel = model.channel,
                showVoiceParticipants = !model.voiceSettings.voiceParticipantsHidden,
                showChannelSettings = PermissionUtils.can(Permission.MANAGE_CHANNELS, model.channelPermissions),
                showActivitiesItem = model.isConnected && model.canUseActivities(),
                members = model.participants.values.filter { it.voiceState != null }.map { participant ->
                    VoiceMember(
                        user = participant.user,
                        name = participant.guildMember?.nick ?: participant.user.username,
                        hasVideo = participant.voiceState?.selfVideo == true,
                        isMuted = participant.isMuted || participant.isDeafened,
                    )
                },
                isDeafened = model.isDeafenedByAnySource,
                showSelfVideo = !model.voiceSettings.selfVideoHidden,
                showVoiceParticipantsToggle = true,
                showInviteItem = model.canInvite() && !isStage,
                noiseProcessing = model.voiceSettings.noiseProcessing,
                showStageSettings = isStage && storeState.myStageRoles?.isModerator == true,
                showReportItem = isStage,
                guild = model.guild,
            )
        }

        fun observeStoreState(
            channelId: Long,
            stageChannelsStore: StoreStageChannels = StoreStream.getStageChannels(),
        ): Observable<StoreState> =
            CallModel.get(channelId)
                .computationLatest()
                .distinctUntilChanged()
                .switchMap { callModel ->
                    if (callModel == null) {
                        Observable.just(StoreState.Invalid)
                    } else {
                        stageChannelsStore.observeMyRoles(channelId)
                            .map<StoreState> { roles -> StoreState.Valid(callModel, roles) }
                    }
                }
    }
}
