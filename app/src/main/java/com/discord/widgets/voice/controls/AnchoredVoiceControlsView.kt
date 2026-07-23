package com.discord.widgets.voice.controls

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceManager
import com.discord.R
import com.discord.app.AppComponent
import com.discord.rtcconnection.audio.DiscordAudioManager
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.stores.StoreAudioManagerV2
import com.discord.tooltips.SparkleView
import com.discord.widgets.voice.model.CameraState
import com.discord.widgets.voice.sheet.WidgetVoiceBottomSheet
import rx.Subscription

class AnchoredVoiceControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val camera: ImageView
    private val disconnect: ImageView
    private val mute: ImageView
    private val ptt: View
    private val screenShare: ImageView
    private val speaker: ImageView
    private var sparkle: SparkleView? = null
    private var sparkleSubscription: Subscription? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.anchored_voice_controls_view, this, true)
        camera = requireNotNull(findViewById(R.id.voice_fullscreen_controls_camera))
        disconnect = requireNotNull(findViewById(R.id.voice_fullscreen_controls_disconnect))
        mute = requireNotNull(findViewById(R.id.voice_fullscreen_controls_mute_state))
        ptt = requireNotNull(findViewById(R.id.voice_fullscreen_controls_ptt))
        screenShare = requireNotNull(findViewById(R.id.voice_fullscreen_controls_screenshare))
        speaker = requireNotNull(findViewById(R.id.voice_fullscreen_controls_speaker))
    }

    @Suppress("LongParameterList", "UNUSED_PARAMETER")
    fun configureUI(
        inputMode: MediaEngineConnection.InputMode,
        audioManagerState: StoreAudioManagerV2.State,
        isMuted: Boolean,
        isScreensharing: Boolean,
        showScreenShareSparkle: Boolean,
        cameraState: CameraState,
        onMutePressed: () -> Unit,
        onScreenSharePressed: () -> Unit,
        onSpeakerButtonPressed: () -> Unit,
        onCameraButtonPressed: () -> Unit,
        onDisconnectPressed: () -> Unit,
        appComponent: AppComponent,
        fragmentManager: FragmentManager,
        onNavigateToScreenShareNfxSheet: () -> Unit,
        channelId: Long,
        guildId: Long,
        forwardToFullscreenIfVideoActivated: Boolean,
        featureContext: WidgetVoiceBottomSheet.FeatureContext,
    ) {
        val state = anchoredVoiceControlsPresentation(
            inputMode,
            audioManagerState.activeAudioDevice,
            isScreensharing,
            cameraState,
        )

        ptt.visibility = if (state.showPtt) VISIBLE else GONE

        camera.visibility = if (state.showCamera) VISIBLE else GONE
        camera.imageTintList = themedTint(state.cameraActive)
        camera.setContentDescription(state.cameraDescription)
        camera.setOnClickListener { onCameraButtonPressed() }

        speaker.setImageResource(state.speakerIcon)
        speaker.imageTintList = themedTint(state.speakerActive)
        speaker.isActivated = state.speakerActivated
        speaker.setOnClickListener { onSpeakerButtonPressed() }

        mute.isActivated = isMuted
        mute.setContentDescription(if (isMuted) R.string.unmute else R.string.mute)
        mute.setOnClickListener { onMutePressed() }

        screenShare.isActivated = isScreensharing
        screenShare.setImageResource(state.screenShareIcon)
        screenShare.setContentDescription(state.screenShareDescription)
        screenShare.setOnClickListener { onScreenSharePressed() }
        configureScreenShareSparkle(
            showScreenShareSparkle,
            appComponent,
            fragmentManager,
            channelId,
            guildId,
            forwardToFullscreenIfVideoActivated,
            featureContext,
            onNavigateToScreenShareNfxSheet,
            onScreenSharePressed,
        )

        disconnect.setOnClickListener { onDisconnectPressed() }
    }

    fun hidePtt() {
        ptt.visibility = GONE
    }

    fun setOnPttPressedListener(onPttPressed: (Boolean) -> Unit) {
        var pressed = false
        ptt.setOnTouchListener { _, event ->
            val next = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> false
                else -> null
            }
            if (next != null && next != pressed) {
                pressed = next
                onPttPressed(next)
            }
            false
        }
    }

    override fun onDetachedFromWindow() {
        removeSparkle()
        super.onDetachedFromWindow()
    }

    private fun configureScreenShareSparkle(
        show: Boolean,
        appComponent: AppComponent,
        fragmentManager: FragmentManager,
        channelId: Long,
        guildId: Long,
        forwardToFullscreenIfVideoActivated: Boolean,
        featureContext: WidgetVoiceBottomSheet.FeatureContext,
        onNavigateToScreenShareNfxSheet: () -> Unit,
        onScreenSharePressed: () -> Unit,
    ) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (!show || preferences.contains(SPARKLE_ACKNOWLEDGED)) {
            removeSparkle()
            return
        }
        if (sparkle != null) return

        sparkleSubscription = appComponent.unsubscribeSignal.subscribe { removeSparkle() }
        sparkle = SparkleView(context, null).also { view ->
            view.setOnClickListener {
                preferences.edit().putBoolean(SPARKLE_ACKNOWLEDGED, true).apply()
                removeSparkle()
                if (WidgetScreenShareNfxSheet.canShow()) {
                    WidgetScreenShareNfxSheet.show(
                        fragmentManager,
                        channelId,
                        guildId,
                        WidgetScreenShareNfxSheet.VoiceBottomSheetParams(
                            forwardToFullscreenIfVideoActivated,
                            featureContext,
                        ),
                    )
                    onNavigateToScreenShareNfxSheet()
                } else {
                    onScreenSharePressed()
                }
            }
            addView(view)
            view.post {
                val bounds = Rect().also(screenShare::getDrawingRect)
                offsetDescendantRectToMyCoords(screenShare, bounds)
                view.x = bounds.exactCenterX() - view.width / 2f
                view.y = bounds.exactCenterY() - view.height / 2f
            }
        }
    }

    private fun removeSparkle() {
        sparkle?.let {
            it.stop()
            removeView(it)
        }
        sparkle = null
        sparkleSubscription?.unsubscribe()
        sparkleSubscription = null
    }

    private fun themedTint(active: Boolean): ColorStateList {
        val value = TypedValue()
        context.theme.resolveAttribute(
            if (active) R.attr.colorInteractiveActive else R.attr.colorInteractiveNormal,
            value,
            true,
        )
        val color = if (value.resourceId != 0) context.getColorCompat(value.resourceId) else value.data
        return ColorStateList.valueOf(color)
    }

    private fun View.setContentDescription(stringRes: Int) {
        contentDescription = context.getString(stringRes)
    }

    private fun Context.getColorCompat(resource: Int): Int =
        androidx.core.content.ContextCompat.getColor(this, resource)

    private companion object {
        const val SPARKLE_ACKNOWLEDGED = "CACHE_KEY_SCREEN_SHARE_BUTTON_SPARKLE_ACKNOWLEDGED"
    }
}

internal data class AnchoredVoiceControlsPresentation(
    val showPtt: Boolean,
    val showCamera: Boolean,
    val cameraActive: Boolean,
    val cameraDescription: Int,
    val speakerIcon: Int,
    val speakerActive: Boolean,
    val speakerActivated: Boolean,
    val screenShareIcon: Int,
    val screenShareDescription: Int,
)

internal fun anchoredVoiceControlsPresentation(
    inputMode: MediaEngineConnection.InputMode,
    activeAudioDevice: DiscordAudioManager.DeviceTypes,
    isScreensharing: Boolean,
    cameraState: CameraState,
) = AnchoredVoiceControlsPresentation(
    showPtt = inputMode == MediaEngineConnection.InputMode.PUSH_TO_TALK,
    showCamera = cameraState != CameraState.CAMERA_DISABLED,
    cameraActive = cameraState == CameraState.CAMERA_ON,
    cameraDescription = if (cameraState == CameraState.CAMERA_ON) R.string.camera_off else R.string.camera_on,
    speakerIcon = if (activeAudioDevice == DiscordAudioManager.DeviceTypes.BLUETOOTH_HEADSET) {
        R.drawable.ic_audio_output_bluetooth_white_24dp
    } else {
        R.drawable.ic_audio_output_white_24dp
    },
    speakerActive = activeAudioDevice != DiscordAudioManager.DeviceTypes.EARPIECE &&
        activeAudioDevice != DiscordAudioManager.DeviceTypes.WIRED_HEADSET,
    speakerActivated = activeAudioDevice == DiscordAudioManager.DeviceTypes.SPEAKERPHONE,
    screenShareIcon = if (isScreensharing) {
        R.drawable.ic_mobile_screenshare_end_24dp
    } else {
        R.drawable.ic_mobile_screenshare_24dp
    },
    screenShareDescription = if (isScreensharing) {
        R.string.stop_streaming
    } else {
        R.string.mobile_stream_screen_share
    },
)
