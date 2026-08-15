package com.discord.widgets.voice.controls

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.discord.R
import com.discord.api.channel.isStageVoiceChannel
import com.discord.stores.StoreMediaSettings
import com.discord.views.NumericBadgingView
import com.discord.views.calls.VolumeSliderView
import com.discord.widgets.stage.StageRoles
import com.discord.widgets.voice.model.CallModel
import com.google.android.material.textview.MaterialTextView
import kotlin.math.roundToInt

class VoiceControlsSheetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private val peekContainer: LinearLayout
    private val videoButton: ImageView
    private val screenShareButton: ImageView
    private val soundboardButton: ImageView
    private val moveToAudienceButton: ImageView
    private val raiseHandBadge: NumericBadgingView
    private val raiseHandButton: ImageView
    private val muteButton: ImageView
    private val stageDisconnectButton: MaterialTextView
    private val stopWatchingButton: ImageView
    private val disconnectButton: ImageView
    private val pushToTalkButton: Button
    private val secondaryActions: View
    private val streamVolumeGroup: View
    private val streamVolumeSlider: VolumeSliderView
    private val activitiesRow: View
    private val activitiesDivider: View
    private val chatRow: View
    private val deafenRow: View
    private val audioOutputRow: View
    private val onlyShowVideosRow: View
    private val showSelfVideoRow: View
    private val inviteRow: View
    private val inviteDivider: View
    private val noiseGroup: View
    private val voiceSettingsRow: View
    private val isGestureNavigationEnabled = isGestureNavigationEnabled()

    init {
        LayoutInflater.from(context).inflate(R.layout.voice_controls_sheet_view, this, true)
        peekContainer = requireNotNull(findViewById(R.id.peek_container))
        videoButton = requireNotNull(findViewById(R.id.video_button))
        screenShareButton = requireNotNull(findViewById(R.id.screen_share_button))
        soundboardButton = requireNotNull(findViewById(R.id.soundboard_button))
        moveToAudienceButton = requireNotNull(findViewById(R.id.move_to_audience_button))
        raiseHandBadge = requireNotNull(findViewById(R.id.raise_hand_badge))
        raiseHandButton = requireNotNull(findViewById(R.id.raise_hand_button))
        muteButton = requireNotNull(findViewById(R.id.mute_button))
        stageDisconnectButton = requireNotNull(findViewById(R.id.stage_disconnect_button_large))
        stopWatchingButton = requireNotNull(findViewById(R.id.stop_watching_button))
        disconnectButton = requireNotNull(findViewById(R.id.disconnect_button))
        pushToTalkButton = requireNotNull(findViewById(R.id.push_to_talk_button))
        secondaryActions = requireNotNull(findViewById(R.id.secondary_actions_card))
        streamVolumeGroup = requireNotNull(findViewById(R.id.controls_stream_volume_group))
        streamVolumeSlider = requireNotNull(findViewById(R.id.stream_volume_slider))
        activitiesRow = requireNotNull(findViewById(R.id.controls_activities))
        activitiesDivider = requireNotNull(findViewById(R.id.controls_activities_divider))
        chatRow = requireNotNull(findViewById(R.id.controls_chat))
        deafenRow = requireNotNull(findViewById(R.id.controls_deafen))
        audioOutputRow = requireNotNull(findViewById(R.id.controls_audio_output))
        onlyShowVideosRow = requireNotNull(findViewById(R.id.controls_only_show_videos))
        showSelfVideoRow = requireNotNull(findViewById(R.id.controls_show_self_video))
        inviteRow = requireNotNull(findViewById(R.id.controls_invite))
        inviteDivider = requireNotNull(findViewById(R.id.controls_invite_divider))
        noiseGroup = requireNotNull(findViewById(R.id.controls_noise_group))
        voiceSettingsRow = requireNotNull(findViewById(R.id.controls_voice_settings))
        secondaryActions.visibility = if (isGestureNavigationEnabled) VISIBLE else INVISIBLE
    }

    @Suppress("LongParameterList")
    fun configureUI(
        model: CallModel,
        outputSelectorState: VoiceControlsOutputSelectorState,
        showVideoButton: Boolean,
        isPushToTalk: Boolean,
        isDeafened: Boolean,
        startedAsVideo: Boolean,
        isStreamFocused: Boolean,
        showDisconnect: Boolean,
        onStopWatchingClick: () -> Unit,
        onDisconnectClick: () -> Unit,
        onAudioOutputClick: () -> Unit,
        onVideoClick: () -> Unit,
        onMuteClick: () -> Unit,
        onInviteClick: () -> Unit,
        onScreenshareClick: () -> Unit,
        onSoundboardClick: () -> Unit,
        showStreamVolume: Boolean,
        streamVolume: Float,
        onStreamVolumeChange: (Float, Boolean) -> Unit,
        onDeafenPressed: () -> Unit,
        requestingToSpeakCount: Int,
        onRaiseHandClick: () -> Unit,
        onMoveToAudienceClick: () -> Unit,
        isUpdatingRequestToSpeak: Boolean,
        stageRoles: StageRoles?,
        onActivitiesClick: () -> Unit,
        onChatClick: () -> Unit,
        onOnlyShowVideosToggle: () -> Unit,
        onSelfVideoToggle: () -> Unit,
        onNoiseProcessingSelect: (StoreMediaSettings.NoiseProcessing) -> Unit,
        onVoiceSettingsClick: () -> Unit,
    ) {
        stopWatchingButton.showCircle(isStreamFocused)
        stopWatchingButton.setOnClickListener { onStopWatchingClick() }

        // Two different notions of "stage" and the reference uses each deliberately.
        // The disconnect chrome keys off whether the user *has* stage roles, because an audience
        // member leaves through a different affordance than a speaker. Everything else keys off
        // the channel type, so the control set stays correct even while roles are still resolving.
        val hasStageRoles = stageRoles != null
        val isStage = model.channel.isStageVoiceChannel()
        val audience = hasStageRoles && stageRoles?.isSpeaker == false
        disconnectButton.showCircle(showDisconnect && !audience)
        stageDisconnectButton.show(showDisconnect && audience)
        disconnectButton.setImageResource(
            if (hasStageRoles) R.drawable.ic_stage_leave_20dp else R.drawable.ic_call_disconnect_24dp,
        )
        disconnectButton.setOnClickListener { onDisconnectClick() }
        stageDisconnectButton.setOnClickListener { onDisconnectClick() }
        pushToTalkButton.show(isPushToTalk && (!hasStageRoles || stageRoles?.isSpeaker == true))

        configureMoveToAudience(stageRoles, onMoveToAudienceClick)
        configureRaiseHand(model, stageRoles, requestingToSpeakCount, isUpdatingRequestToSpeak, onRaiseHandClick)
        configureMute(model, stageRoles, onMuteClick)
        configureVideo(model, onVideoClick, showVideoButton)
        configureScreenShare(model, onScreenshareClick, isStage)
        configureSoundboard(model, onSoundboardClick, isStage)
        configureOutput(outputSelectorState, onAudioOutputClick)
        configureStreamVolume(showStreamVolume, streamVolume, onStreamVolumeChange)
        configureShelf(model, onActivitiesClick, onChatClick)
        configureVoiceGroup(model, isDeafened, onDeafenPressed, onOnlyShowVideosToggle, onSelfVideoToggle)
        configureInvites(model, onInviteClick)
        bindNoiseOptions(model.voiceSettings.noiseProcessing, onNoiseProcessingSelect)
        voiceSettingsRow.bindRow(
            label = R.string.voice_settings,
            icon = R.drawable.ic_settings_24dp,
            sublabel = R.string.voice_panel_voice_settings_subtitle,
        ) { onVoiceSettingsClick() }
    }

    fun setOnPTTListener(listener: OnTouchListener) { pushToTalkButton.setOnTouchListener(listener) }
    fun getPeekHeight() = peekContainer.measuredHeight

    fun handleSheetState(state: Int) {
        secondaryActions.visibility = if (state == SHEET_EXPANDED && !isGestureNavigationEnabled) INVISIBLE else VISIBLE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val metrics = resources.displayMetrics
        layoutParams = layoutParams.apply {
            width = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                metrics.widthPixels
            } else metrics.heightPixels
        }
    }

    private fun configureMute(model: CallModel, roles: StageRoles?, callback: () -> Unit) {
        if (roles != null && !roles.isSpeaker) {
            muteButton.showCircle(false)
            return
        }
        muteButton.showCircle(true)
        val muted = model.isMeMutedByAnySource
        muteButton.setImageResource(
            if (muted) R.drawable.ic_mic_mute_red_strike_24dp else R.drawable.ic_mic_white_24dp,
        )
        muteButton.backgroundTintList = ColorStateList.valueOf(buttonBackground(muted))
        muteButton.contentDescription = context.getString(if (muted) R.string.unmute else R.string.mute)
        muteButton.setOnClickListener { callback() }
    }

    private fun configureOutput(state: VoiceControlsOutputSelectorState, callback: () -> Unit) {
        audioOutputRow.bindRow(
            label = R.string.audio_devices_change_output,
            icon = state.audioOutputIconRes,
        ) { callback() }
    }

    private fun configureVideo(model: CallModel, callback: () -> Unit, shown: Boolean) {
        val active = model.selectedVideoDevice != null
        videoButton.showCircle(shown && model.videoDevices.isNotEmpty())
        videoButton.imageTintList = ColorStateList.valueOf(if (active) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        videoButton.backgroundTintList = ColorStateList.valueOf(buttonBackground(active))
        videoButton.setOnClickListener { callback() }
    }

    private fun configureScreenShare(model: CallModel, callback: () -> Unit, isStage: Boolean) {
        val active = model.isStreaming
        screenShareButton.showCircle(!isStage)
        screenShareButton.setImageResource(
            if (active) R.drawable.ic_mobile_screenshare_end_24dp else R.drawable.ic_mobile_screenshare_24dp,
        )
        screenShareButton.contentDescription = context.getString(
            if (active) R.string.stop_streaming else R.string.mobile_stream_screen_share,
        )
        screenShareButton.imageTintList = ColorStateList.valueOf(if (active) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        screenShareButton.backgroundTintList = ColorStateList.valueOf(buttonBackground(active))
        screenShareButton.setOnClickListener { callback() }
    }

    private fun configureSoundboard(model: CallModel, callback: () -> Unit, isStage: Boolean) {
        soundboardButton.showCircle(model.canUseSoundboard() && !isStage)
        soundboardButton.backgroundTintList = ColorStateList.valueOf(buttonBackground(false))
        soundboardButton.setOnClickListener { callback() }
    }

    private fun configureShelf(model: CallModel, onActivitiesClick: () -> Unit, onChatClick: () -> Unit) {
        val showActivities = model.canUseActivities()
        activitiesRow.show(showActivities)
        activitiesDivider.show(showActivities)
        activitiesRow.bindRow(
            label = R.string.embedded_activities_shelf_title,
            icon = R.drawable.ic_activity_24dp,
        ) { onActivitiesClick() }
        chatRow.bindRow(
            label = R.string.video_call_show_chat,
            icon = R.drawable.ic_chat_message_white_24dp,
        ) { onChatClick() }
    }

    private fun configureVoiceGroup(
        model: CallModel,
        isDeafened: Boolean,
        onDeafenPressed: () -> Unit,
        onOnlyShowVideosToggle: () -> Unit,
        onSelfVideoToggle: () -> Unit,
    ) {
        deafenRow.bindSwitch(
            label = R.string.deafen,
            icon = R.drawable.ic_headset_deafened_white_24dp,
            checked = isDeafened,
            sublabel = R.string.voice_panel_deafen_subtitle,
        ) { onDeafenPressed() }
        onlyShowVideosRow.bindSwitch(
            label = R.string.voice_panel_only_show_videos,
            icon = R.drawable.ic_videocam_white_24dp,
            checked = model.voiceSettings.voiceParticipantsHidden,
            sublabel = R.string.voice_panel_only_show_videos_subtitle,
        ) { onOnlyShowVideosToggle() }
        showSelfVideoRow.bindSwitch(
            label = R.string.voice_panel_show_self_video,
            icon = R.drawable.ic_videocam_white_24dp,
            checked = !model.voiceSettings.selfVideoHidden,
        ) { onSelfVideoToggle() }
    }

    private fun configureInvites(model: CallModel, callback: () -> Unit) {
        inviteRow.show(model.canInvite())
        inviteDivider.show(model.canInvite())
        inviteRow.bindRow(
            label = R.string.invite_a_friend,
            icon = R.drawable.ic_header_members_add_white_24dp,
        ) { callback() }
    }

    private fun bindNoiseOptions(
        selected: StoreMediaSettings.NoiseProcessing,
        callback: (StoreMediaSettings.NoiseProcessing) -> Unit,
    ) {
        listOf(
            Triple(R.id.voice_noise_krisp, R.string.user_settings_noise_cancellation_krisp, StoreMediaSettings.NoiseProcessing.Cancellation),
            Triple(R.id.voice_noise_standard, R.string.user_settings_noise_cancellation_standard, StoreMediaSettings.NoiseProcessing.Suppression),
            Triple(R.id.voice_noise_none, R.string.user_settings_noise_cancellation_disabled, StoreMediaSettings.NoiseProcessing.None),
        ).forEach { (rowId, label, value) ->
            val row = noiseGroup.findViewById<View>(rowId)
            row.findViewById<TextView>(R.id.voice_radio_label).setText(label)
            row.findViewById<CompoundButton>(R.id.voice_radio_button).isChecked = selected == value
            row.setOnClickListener { callback(value) }
        }
    }

    private fun configureStreamVolume(
        shown: Boolean,
        volume: Float,
        callback: (Float, Boolean) -> Unit,
    ) {
        streamVolumeGroup.show(shown)
        streamVolumeSlider.findViewById<SeekBar>(R.id.volume_slider_seek_bar).progress = volume.roundToInt()
        streamVolumeSlider.setOnVolumeChange(callback)
    }

    private fun configureMoveToAudience(roles: StageRoles?, callback: () -> Unit) {
        moveToAudienceButton.showCircle(roles?.isSpeaker == true && !roles.isModerator)
        moveToAudienceButton.backgroundTintList = ColorStateList.valueOf(buttonBackground(false))
        moveToAudienceButton.setOnClickListener { callback() }
    }

    private fun configureRaiseHand(
        model: CallModel,
        roles: StageRoles?,
        count: Int,
        updating: Boolean,
        callback: () -> Unit,
    ) {
        val shown = roles?.isModerator == true || roles?.isAudience == true
        raiseHandBadge.showCircle(shown)
        if (!shown) return
        raiseHandBadge.setBadgeNumber(if (roles?.isModerator == true) count else 0)
        val moderator = roles?.isModerator == true
        val active = !moderator && model.isMyHandRaised
        raiseHandButton.setImageResource(
            if (moderator) R.drawable.ic_stage_raised_hand_list else R.drawable.ic_stage_raised_hand,
        )
        raiseHandButton.isEnabled = moderator || (!updating && model.canRequestToSpeak())
        raiseHandButton.alpha = if (raiseHandButton.isEnabled) 1f else 0.2f
        raiseHandButton.backgroundTintList = ColorStateList.valueOf(buttonBackground(active))
        ImageViewCompat.setImageTintList(raiseHandButton, ColorStateList.valueOf(buttonIcon(active)))
        raiseHandButton.contentDescription = context.getString(
            when {
                moderator -> R.string.request_to_speak_area_title
                !model.canRequestToSpeak() -> R.string.audience_raise_hand_no_permission
                model.isMyHandRaised -> R.string.audience_lower_hand
                else -> R.string.audience_raise_hand_cta
            },
        )
        raiseHandButton.setOnClickListener { if (raiseHandButton.isEnabled) callback() }
    }

    private fun buttonBackground(active: Boolean) = ContextCompat.getColor(
        context,
        if (active) R.color.white else R.color.white_alpha_24,
    )

    private fun buttonIcon(active: Boolean): Int {
        if (!active) return ContextCompat.getColor(context, R.color.white)
        val value = TypedValue()
        context.theme.resolveAttribute(R.attr.call_controls_active_button_icon_color, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }

    private fun isGestureNavigationEnabled(): Boolean {
        val id = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
        return id != 0 && resources.getInteger(id) == 2
    }

    private fun View.show(show: Boolean) { visibility = if (show) VISIBLE else GONE }

    /** The row shares out the width, so hiding a control means hiding the slot it sits in. */
    private fun View.showCircle(show: Boolean) { (parent as View).show(show) }

    private fun View.bindRow(
        @StringRes label: Int,
        @DrawableRes icon: Int,
        @StringRes sublabel: Int? = null,
        onClick: () -> Unit,
    ) {
        findViewById<ImageView>(R.id.voice_row_icon).setImageResource(icon)
        findViewById<TextView>(R.id.voice_row_label).setText(label)
        findViewById<TextView>(R.id.voice_row_sublabel).apply {
            show(sublabel != null)
            if (sublabel != null) setText(sublabel)
        }
        setOnClickListener { onClick() }
    }

    private fun View.bindSwitch(
        @StringRes label: Int,
        @DrawableRes icon: Int,
        checked: Boolean,
        @StringRes sublabel: Int? = null,
        onClick: () -> Unit,
    ) {
        bindRow(label, icon, sublabel, onClick)
        findViewById<CompoundButton>(R.id.voice_row_switch).isChecked = checked
    }

    private companion object {
        const val SHEET_EXPANDED = 4
    }
}
