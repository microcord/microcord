package com.discord.views.calls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.DisplayCutoutCompat
import com.discord.R
import com.discord.i18n.i18nFormat
import com.discord.stores.StoreApplicationStreamPreviews
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.view.extensions.fadeBy
import com.discord.views.VoiceUserView
import com.discord.views.VitoImageView
import org.webrtc.RendererCommon

class VideoCallParticipantView @JvmOverloads @SuppressLint("ResourceType") constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    data class ParticipantData(
        val voiceParticipant: StoreVoiceParticipants.VoiceUser,
        val mirror: Boolean,
        val scalingType: RendererCommon.ScalingType?,
        val insetScalingType: RendererCommon.ScalingType?,
        val applicationStreamState: ApplicationStreamState?,
        val type: Type,
        val showLabel: Boolean,
        val isFullScreen: Boolean,
        val streamQuality: StreamQuality?,
    ) {
        val id = "${voiceParticipant.user.id}$type"
        val streamId: Int?
            get() = when (type) {
                Type.DEFAULT -> voiceParticipant.callStreamId.takeIf {
                    voiceParticipant.voiceState?.selfVideo == true
                }
                Type.APPLICATION_STREAMING -> voiceParticipant.applicationStreamId.takeIf {
                    applicationStreamState in setOf(
                        ApplicationStreamState.CONNECTING,
                        ApplicationStreamState.ACTIVE,
                        ApplicationStreamState.PAUSED,
                    ) && voiceParticipant.voiceState?.selfStream == true
                }
            }

        fun createView(context: Context) = VideoCallParticipantView(context)

        enum class Type { DEFAULT, APPLICATION_STREAMING }
        enum class ApplicationStreamState { CONNECTING, ACTIVE, INACTIVE, PAUSED, ENDED }
        data class StreamQuality(
            val isPremium: Boolean,
            val resolution: StreamResolution,
            val fps: StreamFps?,
            val isPoorQuality: Boolean,
        )
    }

    sealed class StreamResolution {
        abstract fun label(context: Context): CharSequence
        val isHighDefinition get() = this is Source || this is Fixed && value == 1080

        data class Fixed(val value: Int) : StreamResolution() {
            override fun label(context: Context) =
                context.i18nFormat(R.string.screenshare_resolution_abbreviated, "resolution" to value)
        }

        data object Source : StreamResolution() {
            override fun label(context: Context) = context.getString(R.string.screenshare_source)
        }
    }

    data class StreamFps(val value: Int) {
        val isHighFrameRate get() = value == 60
    }

    var data: ParticipantData? = null
        private set
    val dataId: String? get() = data?.id
    var onStreamQualityIndicatorShown: (StreamResolution, StreamFps?) -> Unit = { _, _ -> }
    var onStreamQualityIndicatorClicked: (StreamResolution, StreamFps?) -> Unit = { _, _ -> }
    private var onWatchStreamClicked: (String) -> Unit = {}
    private var displayCutout: DisplayCutoutCompat? = null
    private var isTopRow = false
    private var callUiInsets = CallUiInsets()
    private var showStreamQuality = false
    private var labelInitialized = false
    private var boundStreamId: Int? = null
    private var matchVideoOrientation = false

    private val avatarBackground: View
    private val letterbox: View
    private val voiceUser: VoiceUserView
    private val renderer: AppVideoStreamRenderer
    private val preview: VitoImageView
    private val previewText: TextView
    private val paused: TextView
    private val ended: View
    private val loading: ProgressBar
    private val label: TextView
    private val mute: ImageView
    private val deafen: ImageView
    private val indicators: LinearLayout
    private val live: TextView
    private val qualityContainer: View
    private val qualityIcon: ImageView
    private val qualityInfo: TextView
    private val qualityLive: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_video_call_participant, this, true)
        avatarBackground = requireNotNull(findViewById(R.id.participant_bg_avatar))
        letterbox = requireNotNull(findViewById(R.id.participant_bg_letterbox))
        voiceUser = requireNotNull(findViewById(R.id.participant_voice_user_view))
        renderer = requireNotNull(findViewById(R.id.participant_video_stream_renderer))
        preview = requireNotNull(findViewById(R.id.participant_stream_preview_image))
        previewText = requireNotNull(findViewById(R.id.participant_stream_preview_text))
        paused = requireNotNull(findViewById(R.id.participant_stream_paused))
        ended = requireNotNull(findViewById(R.id.participant_stream_ended))
        loading = requireNotNull(findViewById(R.id.participant_video_loading_indicator))
        label = requireNotNull(findViewById(R.id.participant_video_label))
        mute = requireNotNull(findViewById(R.id.participant_mute_status_indicator))
        deafen = requireNotNull(findViewById(R.id.participant_deafen_status_indicator))
        indicators = requireNotNull(findViewById(R.id.participant_voice_indicators))
        live = requireNotNull(findViewById(R.id.participant_live_indicator))
        qualityContainer = requireNotNull(findViewById(R.id.participant_fullscreen_streamqual_container))
        qualityIcon = requireNotNull(findViewById(R.id.participant_fullscreen_streamqual_icon))
        qualityInfo = requireNotNull(findViewById(R.id.participant_fullscreen_streamqual_info))
        qualityLive = requireNotNull(findViewById(R.id.participant_fullscreen_streamqual_live_indicator))
        voiceUser.setOnBitmapLoadedListener { bitmap, _ -> avatarBackground.setBackgroundColor(bitmap.averageColor()) }
        qualityContainer.setOnClickListener {
            data?.streamQuality?.let { onStreamQualityIndicatorClicked(it.resolution, it.fps) }
        }

        // The overlay tile draws over other windows and the fullscreen tile rotates
        // to follow the incoming video; both are declared per layout.
        var isOverlay = false
        if (attrs != null) {
            context.obtainStyledAttributes(
                attrs,
                intArrayOf(R.attr.vcpv_is_overlay, R.attr.vcpv_match_video_orientation),
            ).run {
                try {
                    isOverlay = getBoolean(0, false)
                    matchVideoOrientation = getBoolean(1, false)
                } finally {
                    recycle()
                }
            }
        }
        renderer.setIsOverlay(isOverlay)
        renderer.setMatchVideoOrientation(matchVideoOrientation)
    }

    /**
     * Every parameter but [data] defaults to the value already in effect, so a
     * rebind from the grid keeps the cutout, row position, call insets and control
     * visibility the host configured once.
     */
    @JvmOverloads
    fun configure(
        data: ParticipantData?,
        displayCutout: DisplayCutoutCompat? = this.displayCutout,
        isTopRow: Boolean = this.isTopRow,
        callUiInsets: CallUiInsets = this.callUiInsets,
        showStreamQuality: Boolean = this.showStreamQuality,
    ) {
        this.displayCutout = displayCutout
        this.isTopRow = isTopRow
        this.callUiInsets = callUiInsets
        this.showStreamQuality = showStreamQuality
        this.data = data
        render(data)
    }

    fun onBind(data: ParticipantData) = configure(data)
    fun onRemove() = detachRenderer()
    fun setOnWatchStreamClicked(listener: (String) -> Unit) { onWatchStreamClicked = listener }

    private fun render(data: ParticipantData?) {
        val participant = data?.voiceParticipant
        participant?.let {
            voiceUser.setVoiceUser(it, R.dimen.avatar_size_large)
            voiceUser.setAvatarDecoration(it.guildMember?.avatarDecoration ?: it.user.avatarDecoration)
        }
        mute.visibility = visible(participant?.isMuted == true && data?.type == ParticipantData.Type.DEFAULT)
        deafen.visibility = visible(participant?.isDeafened == true && data?.type == ParticipantData.Type.DEFAULT)
        indicators.visibility = visible(mute.visibility == VISIBLE || deafen.visibility == VISIBLE)

        resetStreamState()
        if (data?.type == ParticipantData.Type.APPLICATION_STREAMING) {
            avatarBackground.visibility = GONE
            voiceUser.visibility = GONE
            when (data.applicationStreamState) {
                ParticipantData.ApplicationStreamState.CONNECTING -> {
                    preview.visibility = VISIBLE
                    loading.visibility = VISIBLE
                    live.visibility = visible(!data.isFullScreen)
                }
                ParticipantData.ApplicationStreamState.ACTIVE -> live.visibility = visible(!data.isFullScreen)
                ParticipantData.ApplicationStreamState.INACTIVE -> {
                    preview.visibility = VISIBLE
                    previewText.visibility = VISIBLE
                    previewText.setOnClickListener {
                        participant?.streamContext?.stream?.encodedStreamKey?.let(onWatchStreamClicked)
                    }
                    val resolved = participant?.streamContext?.preview as? StoreApplicationStreamPreviews.StreamPreview.Resolved
                    preview.setImageURI(resolved?.url)
                }
                ParticipantData.ApplicationStreamState.PAUSED -> {
                    preview.visibility = VISIBLE
                    paused.visibility = VISIBLE
                    live.visibility = visible(!data.isFullScreen)
                }
                ParticipantData.ApplicationStreamState.ENDED -> ended.visibility = VISIBLE
                null -> Unit
            }
        } else {
            avatarBackground.visibility = visible(data?.streamId == null)
            voiceUser.visibility = visible(data?.streamId == null)
        }

        val streamChanged = boundStreamId != data?.streamId
        if (streamChanged) {
            renderer.visibility = GONE
            loading.visibility = visible(data?.streamId != null)
        }
        boundStreamId = data?.streamId
        renderer.configure(data?.streamId, data?.scalingType, data?.insetScalingType, data?.mirror == true)
        renderer.onFrameRenderedListener = { frame: Point ->
            renderer.visibility = VISIBLE
            loading.visibility = GONE
            letterbox.visibility = visible(preview.visibility != VISIBLE)
            preview.visibility = GONE
            previewText.visibility = GONE
            if (matchVideoOrientation && (frame.x > frame.y) != (width > height)) {
                // A portrait tile showing landscape video (or the reverse) swaps its
                // own dimensions so the frame is not letterboxed to nothing.
                layoutParams = layoutParams.apply {
                    val previousWidth = width
                    width = height
                    height = previousWidth
                }
            }
        }

        configureQuality(data)
        configureLabel(data)
        configureCutout()
    }

    private fun resetStreamState() {
        preview.visibility = GONE
        previewText.visibility = GONE
        paused.visibility = GONE
        ended.visibility = GONE
        live.visibility = GONE
        letterbox.visibility = GONE
    }

    private fun configureQuality(data: ParticipantData?) {
        val quality = data?.streamQuality
        val shown = data?.isFullScreen == true && showStreamQuality && quality != null &&
            data.applicationStreamState in setOf(
                ParticipantData.ApplicationStreamState.ACTIVE,
                ParticipantData.ApplicationStreamState.PAUSED,
            )
        if (!shown || quality == null) {
            qualityContainer.fadeBy(false, FADE_DURATION_MS)
            return
        }
        qualityContainer.translationX = -callUiInsets.right.toFloat()
        qualityContainer.translationY = callUiInsets.top.toFloat()
        qualityIcon.visibility = visible(quality.isPremium)
        qualityInfo.text = listOfNotNull(
            quality.resolution.label(context).toString().takeIf(String::isNotBlank),
            quality.fps?.let { context.i18nFormat(R.string.stream_fps_option, "value" to it.value) },
        ).joinToString(" ")
        qualityInfo.visibility = visible(qualityInfo.text.isNotEmpty())
        qualityContainer.fadeBy(true, FADE_DURATION_MS)
        qualityLive.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                context,
                if (quality.isPoorQuality) R.color.status_yellow_500 else R.color.white,
            ),
        )
        onStreamQualityIndicatorShown(quality.resolution, quality.fps)
    }

    private fun configureLabel(data: ParticipantData?) {
        val participant = data?.voiceParticipant ?: return
        label.text = participant.displayName
        label.setCompoundDrawablesWithIntrinsicBounds(
            if (data.type == ParticipantData.Type.APPLICATION_STREAMING) R.drawable.ic_screen_14dp else 0,
            0,
            0,
            0,
        )
        contentDescription = if (data.type == ParticipantData.Type.APPLICATION_STREAMING) {
            context.i18nFormat(R.string.watch_user_stream, "streamerName" to participant.displayName)
        } else participant.displayName
        // The first bind sets the label outright; only later changes cross-fade, so
        // a freshly attached tile does not flash its label in.
        if (labelInitialized) label.fadeBy(data.showLabel, FADE_DURATION_MS)
        else label.visibility = visible(data.showLabel)
        labelInitialized = true
    }

    private fun configureCutout() {
        val insetTop = displayCutout?.safeInsetTop ?: 0
        val set = ConstraintSet().apply { clone(this@VideoCallParticipantView) }
        // A top-row tile on a device with a notch moves the LIVE pill to the bottom
        // so it is not hidden by the cutout, unless the mute/deafen row already
        // occupies that corner.
        val moveToBottom = insetTop > 0 && isTopRow && indicators.visibility != VISIBLE
        set.connect(live.id, ConstraintSet.RIGHT, ConstraintSet.PARENT_ID, ConstraintSet.RIGHT, dpToPixels(8))
        if (moveToBottom) {
            set.connect(live.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, dpToPixels(8))
            set.clear(live.id, ConstraintSet.TOP)
        } else {
            set.connect(live.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, dpToPixels(8))
            set.clear(live.id, ConstraintSet.BOTTOM)
        }
        set.applyTo(this)
    }

    override fun onDetachedFromWindow() {
        detachRenderer()
        labelInitialized = false
        super.onDetachedFromWindow()
    }

    private fun detachRenderer() {
        renderer.onFrameRenderedListener = null
        renderer.configure(null, null, null, false)
        boundStreamId = null
    }

    private fun visible(show: Boolean) = if (show) VISIBLE else GONE

    private fun Bitmap.averageColor(): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        val stepX = (width / 8).coerceAtLeast(1)
        val stepY = (height / 8).coerceAtLeast(1)
        for (x in 0 until width step stepX) for (y in 0 until height step stepY) {
            val color = getPixel(x, y)
            red += android.graphics.Color.red(color)
            green += android.graphics.Color.green(color)
            blue += android.graphics.Color.blue(color)
            count++
        }
        return android.graphics.Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    data class CallUiInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

    private companion object {
        const val FADE_DURATION_MS = 200L
    }
}
