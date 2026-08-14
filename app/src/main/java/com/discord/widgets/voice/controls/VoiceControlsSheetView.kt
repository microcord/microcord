package com.discord.widgets.voice.controls

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.discord.R
import com.discord.api.channel.isStageVoiceChannel
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
    private val soundboardSecondaryButton: TextView
    private val moveToAudienceButton: ImageView
    private val raiseHandBadge: NumericBadgingView
    private val raiseHandButton: ImageView
    private val inviteButton: ImageView
    private val audioOutputContainer: FrameLayout
    private val audioOutputButton: ImageView
    private val audioOutputMore: ImageView
    private val muteButton: ImageView
    private val stageDisconnectButton: MaterialTextView
    private val stopWatchingButton: ImageView
    private val disconnectButton: ImageView
    private val pushToTalkButton: Button
    private val secondaryActions: CardView
    private val streamVolumeLabel: TextView
    private val streamVolumeSlider: VolumeSliderView
    private val audioOutputSecondaryButton: TextView
    private val screenShareSecondaryButton: TextView
    private val deafenSecondaryButton: TextView
    private val inviteSecondaryButton: TextView
    private val isGestureNavigationEnabled = isGestureNavigationEnabled()

    init {
        LayoutInflater.from(context).inflate(R.layout.voice_controls_sheet_view, this, true)
        peekContainer = requireNotNull(findViewById(R.id.peek_container))
        videoButton = requireNotNull(findViewById(R.id.video_button))
        screenShareButton = requireNotNull(findViewById(R.id.screen_share_button))
        soundboardSecondaryButton = requireNotNull(findViewById(R.id.soundboard_secondary_button))
        moveToAudienceButton = requireNotNull(findViewById(R.id.move_to_audience_button))
        raiseHandBadge = requireNotNull(findViewById(R.id.raise_hand_badge))
        raiseHandButton = requireNotNull(findViewById(R.id.raise_hand_button))
        inviteButton = requireNotNull(findViewById(R.id.invite_button))
        audioOutputContainer = requireNotNull(findViewById(R.id.audio_output_container))
        audioOutputButton = requireNotNull(findViewById(R.id.audio_output_button))
        audioOutputMore = requireNotNull(findViewById(R.id.audio_output_more))
        muteButton = requireNotNull(findViewById(R.id.mute_button))
        stageDisconnectButton = requireNotNull(findViewById(R.id.stage_disconnect_button_large))
        stopWatchingButton = requireNotNull(findViewById(R.id.stop_watching_button))
        disconnectButton = requireNotNull(findViewById(R.id.disconnect_button))
        pushToTalkButton = requireNotNull(findViewById(R.id.push_to_talk_button))
        secondaryActions = requireNotNull(findViewById(R.id.secondary_actions_card))
        streamVolumeLabel = requireNotNull(findViewById(R.id.stream_volume_label))
        streamVolumeSlider = requireNotNull(findViewById(R.id.stream_volume_slider))
        audioOutputSecondaryButton = requireNotNull(findViewById(R.id.audio_output_secondary_button))
        screenShareSecondaryButton = requireNotNull(findViewById(R.id.screen_share_secondary_button))
        deafenSecondaryButton = requireNotNull(findViewById(R.id.deafen_secondary_button))
        inviteSecondaryButton = requireNotNull(findViewById(R.id.invite_secondary_button))
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
    ) {
        stopWatchingButton.show(isStreamFocused)
        stopWatchingButton.setOnClickListener { onStopWatchingClick() }

        // Two different notions of "stage" and the reference uses each deliberately.
        // The disconnect chrome keys off whether the user *has* stage roles, because an audience
        // member leaves through a different affordance than a speaker. Everything else keys off
        // the channel type, so the control set stays correct even while roles are still resolving.
        val hasStageRoles = stageRoles != null
        val isStage = model.channel.isStageVoiceChannel()
        val audience = hasStageRoles && stageRoles?.isSpeaker == false
        disconnectButton.show(showDisconnect && !audience)
        stageDisconnectButton.show(showDisconnect && audience)
        disconnectButton.setImageResource(
            if (hasStageRoles) R.drawable.ic_stage_leave_20dp else R.drawable.ic_call_disconnect_24dp,
        )
        disconnectButton.setOnClickListener { onDisconnectClick() }
        stageDisconnectButton.setOnClickListener { onDisconnectClick() }
        pushToTalkButton.show(isPushToTalk && (!hasStageRoles || stageRoles?.isSpeaker == true))

        deafenSecondaryButton.setText(if (isDeafened) R.string.undeafen else R.string.deafen)
        deafenSecondaryButton.isActivated = isDeafened
        deafenSecondaryButton.setOnClickListener { onDeafenPressed() }

        configureMoveToAudience(stageRoles, onMoveToAudienceClick)
        configureRaiseHand(model, stageRoles, requestingToSpeakCount, isUpdatingRequestToSpeak, onRaiseHandClick)
        configureMute(model, stageRoles, onMuteClick)
        configureOutput(outputSelectorState, onAudioOutputClick, !startedAsVideo && !isStage)
        configureVideo(model, onVideoClick, showVideoButton)
        configureScreenShare(model, onScreenshareClick, startedAsVideo, isStage)
        // A sheet row rather than a circle: it sits with the other secondary actions regardless of
        // whether the call is showing its primary controls.
        soundboardSecondaryButton.show(model.canUseSoundboard() && !isStage)
        soundboardSecondaryButton.setOnClickListener { onSoundboardClick() }
        configureStreamVolume(showStreamVolume, streamVolume, onStreamVolumeChange)
        configureInvites(model, onInviteClick, isStage)
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
            muteButton.visibility = GONE
            return
        }
        muteButton.visibility = VISIBLE
        val muted = model.isMeMutedByAnySource
        muteButton.setImageResource(
            if (muted) R.drawable.ic_mic_mute_red_strike_24dp else R.drawable.ic_mic_white_24dp,
        )
        muteButton.backgroundTintList = ColorStateList.valueOf(buttonBackground(muted))
        muteButton.contentDescription = context.getString(if (muted) R.string.unmute else R.string.mute)
        muteButton.setOnClickListener { callback() }
    }

    private fun configureOutput(
        state: VoiceControlsOutputSelectorState,
        callback: () -> Unit,
        primary: Boolean,
    ) {
        audioOutputContainer.show(primary)
        audioOutputSecondaryButton.show(!primary)
        audioOutputButton.setImageResource(state.audioOutputIconRes)
        audioOutputSecondaryButton.setCompoundDrawablesWithIntrinsicBounds(state.audioOutputIconRes, 0, 0, 0)
        audioOutputButton.backgroundTintList = ColorStateList.valueOf(buttonBackground(state.isButtonActive))
        val tint = buttonIcon(state.isButtonActive)
        ImageViewCompat.setImageTintList(audioOutputButton, ColorStateList.valueOf(tint))
        ImageViewCompat.setImageTintList(audioOutputMore, ColorStateList.valueOf(tint))
        audioOutputMore.show(state.showMoreOptions)
        audioOutputButton.setOnClickListener { callback() }
        audioOutputSecondaryButton.setOnClickListener { callback() }
    }

    private fun configureVideo(model: CallModel, callback: () -> Unit, shown: Boolean) {
        val active = model.selectedVideoDevice != null
        videoButton.show(shown && model.videoDevices.isNotEmpty())
        videoButton.imageTintList = ColorStateList.valueOf(if (active) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        videoButton.backgroundTintList = ColorStateList.valueOf(buttonBackground(active))
        videoButton.setOnClickListener { callback() }
    }

    private fun configureScreenShare(
        model: CallModel,
        callback: () -> Unit,
        primary: Boolean,
        isStage: Boolean,
    ) {
        val active = model.isStreaming
        val icon =
            if (active) R.drawable.ic_mobile_screenshare_end_24dp else R.drawable.ic_mobile_screenshare_24dp
        screenShareButton.show(primary && !isStage)
        screenShareSecondaryButton.show(!primary && !isStage)
        screenShareButton.setImageResource(icon)
        screenShareButton.contentDescription = context.getString(
            if (active) R.string.stop_streaming else R.string.mobile_stream_screen_share,
        )
        screenShareSecondaryButton.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
        screenShareSecondaryButton.setText(
            if (active) R.string.stop_streaming else R.string.mobile_stream_screen_share,
        )
        screenShareButton.imageTintList = ColorStateList.valueOf(if (active) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        screenShareButton.backgroundTintList = ColorStateList.valueOf(buttonBackground(active))
        screenShareButton.setOnClickListener { callback() }
        screenShareSecondaryButton.setOnClickListener { callback() }
    }

    private fun configureInvites(model: CallModel, callback: () -> Unit, primary: Boolean) {
        inviteButton.show(model.canInvite() && primary)
        inviteSecondaryButton.show(model.canInvite() && !primary)
        inviteButton.backgroundTintList = ColorStateList.valueOf(buttonBackground(false))
        inviteButton.setOnClickListener { callback() }
        inviteSecondaryButton.setOnClickListener { callback() }
    }

    private fun configureStreamVolume(
        shown: Boolean,
        volume: Float,
        callback: (Float, Boolean) -> Unit,
    ) {
        streamVolumeLabel.show(shown)
        streamVolumeSlider.show(shown)
        streamVolumeSlider.findViewById<SeekBar>(R.id.volume_slider_seek_bar).progress = volume.roundToInt()
        streamVolumeSlider.setOnVolumeChange(callback)
    }

    private fun configureMoveToAudience(roles: StageRoles?, callback: () -> Unit) {
        moveToAudienceButton.show(roles?.isSpeaker == true && !roles.isModerator)
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
        raiseHandBadge.show(shown)
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

    private companion object {
        const val SHEET_EXPANDED = 4
    }
}
