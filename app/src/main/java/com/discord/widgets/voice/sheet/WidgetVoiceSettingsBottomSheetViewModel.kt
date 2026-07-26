package com.discord.widgets.voice.sheet

import androidx.annotation.MainThread
import com.discord.api.channel.Channel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.stores.StoreMediaSettings
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
    private val mediaSettingsStore: StoreMediaSettings = StoreStream.getMediaSettings(),
    val storeObservable: Observable<StoreState> = observeStoreState(channelId),
) : AppViewModel<WidgetVoiceSettingsBottomSheetViewModel.ViewState>() {

    init {
        storeObservable
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
        override val showVoiceParticipantsToggle: Boolean,
        override val showInviteItem: Boolean,
        override val noiseCancellationEnabled: Boolean,
        override val showStageSettings: Boolean,
        override val showReportItem: Boolean,
        override val guild: Guild?,
    ) : WidgetVoiceSettingsBottomSheet.ViewState

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        if (storeState !is StoreState.Valid) return
        val callModel = storeState.callModel
        val isStage = callModel.channel.isStageVoiceChannel()
        updateViewState(
            ViewState(
                channel = callModel.channel,
                showVoiceParticipants = !callModel.voiceSettings.voiceParticipantsHidden,
                // Hiding non-video participants only means anything once there is video.
                showVoiceParticipantsToggle =
                    callModel.isVideoCall || callModel.activeStream != null,
                showInviteItem = callModel.canInvite() && !isStage,
                noiseCancellationEnabled = callModel.voiceSettings.noiseProcessing ==
                    StoreMediaSettings.NoiseProcessing.Cancellation,
                showStageSettings = isStage && storeState.myStageRoles?.isModerator == true,
                showReportItem = isStage,
                guild = callModel.guild,
            ),
        )
    }

    @MainThread
    fun onToggleNoiseCancellation() {
        mediaSettingsStore.toggleNoiseCancellation()
    }

    @MainThread
    fun onToggleVoiceParticipantsHidden() {
        // The current state *is* the new hidden flag: the switch shows "show participants", so
        // toggling it off is what hides them.
        val currentViewState = viewState ?: return
        mediaSettingsStore.updateVoiceParticipantsHidden(currentViewState.showVoiceParticipants)
    }

    companion object {
        /**
         * The reference additionally observes `StoreStageChannels` for the current user's
         * [StageRoles]. That store has not been reconstructed (see `followups.md`), so the roles
         * are always `null` here and the **stage-settings row never appears**. Feed the roles in
         * when `StoreStageChannels` lands and the row lights up unchanged.
         */
        fun observeStoreState(channelId: Long): Observable<StoreState> =
            CallModel.get(channelId)
                .computationLatest()
                .distinctUntilChanged()
                .map { callModel ->
                    if (callModel == null) {
                        StoreState.Invalid
                    } else {
                        StoreState.Valid(callModel, myStageRoles = null)
                    }
                }
    }
}
