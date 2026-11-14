package com.discord.views.calls

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.DisplayCutoutCompat
import com.discord.R
import com.discord.databinding.ViewVideoCallParticipantBinding
import com.discord.i18n.i18nFormat
import com.discord.stores.StoreApplicationStreamPreviews
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.view.extensions.fadeBy
import com.discord.views.VoiceUserView
import com.discord.views.VitoImageView
import org.webrtc.RendererCommon
import kotlin.math.hypot

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
        /** Offers watching this stream alongside another one already playing. */
        val canWatchAlongside: Boolean = false,
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
    /** Called with the stream key and whether to keep the streams already being watched. */
    private var onWatchStreamClicked: (String, Boolean) -> Unit = { _, _ -> }
    private var displayCutout: DisplayCutoutCompat? = null
    private var isTopRow = false
    private var callUiInsets = CallUiInsets()
    private var showStreamQuality = false
    private var labelInitialized = false
    private var boundStreamId: Int? = null
    private var matchVideoOrientation = false

    private var fitToFrame = false

    /**
     * The focused tile fits its picture in the draw, so its renderer fills the tile and zoom never
     * resizes it; other fitted tiles shape the renderer to the picture instead.
     */
    private var drawFits = false

    /** The incoming picture's size as shown (after rotation), once a frame has arrived. */
    private var lastFrame: Point? = null

    /** Zoom factor and centre, the centre as fractions of the unzoomed picture. */
    private var zoom = 1f
    private var zoomCenterX = 0.5f
    private var zoomCenterY = 0.5f

    /** Set once a touch turns into a pinch or pan; the tile's own tap and long press are cancelled. */
    private var zoomGestureActive = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Moving both fingers together drags the picture as well as zooming it.
            panBy(detector.focusX - lastFocusX, detector.focusY - lastFocusY)
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }
    })
    private val doubleTap = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (zoom <= 1f) return false
            // The second tap is the reset; the tile must not also take it as a tap.
            startZoomGesture(event)
            animateZoomOut()
            return true
        }
    }).apply { setIsLongpressEnabled(false) }
    private var zoomOutAnimator: ValueAnimator? = null

    private val binding = ViewVideoCallParticipantBinding.inflate(LayoutInflater.from(context), this)

    init {
        binding.participantVoiceUserView.setOnBitmapLoadedListener { bitmap, _ ->
            binding.participantBgAvatar.setBackgroundColor(bitmap.averageColor())
        }
        binding.participantFullscreenStreamqualContainer.setOnClickListener {
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
        binding.participantVideoStreamRenderer.setIsOverlay(isOverlay)
        binding.participantVideoStreamRenderer.setMatchVideoOrientation(matchVideoOrientation)
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
    fun setOnWatchStreamClicked(listener: (String, Boolean) -> Unit) { onWatchStreamClicked = listener }

    private fun render(data: ParticipantData?) {
        val participant = data?.voiceParticipant
        participant?.let {
            binding.participantVoiceUserView.setVoiceUser(it, R.dimen.avatar_size_large)
            binding.participantVoiceUserView.setAvatarDecoration(it.guildMember?.avatarDecoration ?: it.user.avatarDecoration)
        }
        binding.participantMuteStatusIndicator.visibility = visible(participant?.isMuted == true && data?.type == ParticipantData.Type.DEFAULT)
        binding.participantDeafenStatusIndicator.visibility = visible(participant?.isDeafened == true && data?.type == ParticipantData.Type.DEFAULT)
        binding.participantVoiceIndicators.visibility = visible(binding.participantMuteStatusIndicator.visibility == VISIBLE || binding.participantDeafenStatusIndicator.visibility == VISIBLE)

        resetStreamState()
        if (data?.type == ParticipantData.Type.APPLICATION_STREAMING) {
            binding.participantBgAvatar.visibility = GONE
            binding.participantVoiceUserView.visibility = GONE
            when (data.applicationStreamState) {
                ParticipantData.ApplicationStreamState.CONNECTING -> {
                    binding.participantStreamPreviewImage.visibility = VISIBLE
                    binding.participantVideoLoadingIndicator.visibility = VISIBLE
                    binding.participantLiveIndicator.visibility = visible(!data.isFullScreen)
                }
                ParticipantData.ApplicationStreamState.ACTIVE -> binding.participantLiveIndicator.visibility = visible(!data.isFullScreen)
                ParticipantData.ApplicationStreamState.INACTIVE -> {
                    binding.participantStreamPreviewImage.visibility = VISIBLE
                    binding.participantStreamPreviewText.visibility = VISIBLE
                    val streamKey = participant?.streamContext?.stream?.encodedStreamKey
                    binding.participantStreamPreviewText.setOnClickListener {
                        streamKey?.let { onWatchStreamClicked(it, false) }
                    }
                    binding.participantStreamPreviewAdd.visibility = visible(data.canWatchAlongside)
                    binding.participantStreamPreviewAdd.setOnClickListener {
                        streamKey?.let { onWatchStreamClicked(it, true) }
                    }
                    val resolved = participant?.streamContext?.preview as? StoreApplicationStreamPreviews.StreamPreview.Resolved
                    binding.participantStreamPreviewImage.setImageURI(resolved?.url)
                }
                ParticipantData.ApplicationStreamState.PAUSED -> {
                    binding.participantStreamPreviewImage.visibility = VISIBLE
                    binding.participantStreamPaused.visibility = VISIBLE
                    binding.participantLiveIndicator.visibility = visible(!data.isFullScreen)
                }
                ParticipantData.ApplicationStreamState.ENDED -> binding.participantStreamEnded.visibility = VISIBLE
                null -> Unit
            }
        } else {
            binding.participantBgAvatar.visibility = visible(data?.streamId == null)
            binding.participantVoiceUserView.visibility = visible(data?.streamId == null)
        }

        val streamChanged = boundStreamId != data?.streamId
        if (streamChanged) {
            binding.participantVideoStreamRenderer.visibility = GONE
            binding.participantVideoLoadingIndicator.visibility = visible(data?.streamId != null)
        }
        boundStreamId = data?.streamId
        binding.participantVideoStreamRenderer.configure(data?.streamId, data?.scalingType, data?.insetScalingType, data?.mirror == true)
        fitToFrame = data?.scalingType == RendererCommon.ScalingType.SCALE_ASPECT_FIT
        drawFits = data?.isFullScreen == true && fitToFrame
        if (streamChanged) lastFrame = null
        if (streamChanged || !drawFits) resetZoom()
        if (drawFits || !fitToFrame) fitRendererTo(null)
        binding.participantVideoStreamRenderer.setFitZoom(zoom.takeIf { drawFits }, zoomCenterX, zoomCenterY)
        binding.participantVideoStreamRenderer.onFrameRenderedListener = { frame: Point ->
            lastFrame = frame.takeIf { it.x > 0 && it.y > 0 }
            // The renderer crops every frame to its own shape, so "fit" only shows the whole frame
            // once the view itself takes the frame's aspect ratio; the letterbox fills the rest.
            if (!drawFits) fitRendererTo(lastFrame.takeIf { fitToFrame })
            binding.participantVideoStreamRenderer.visibility = VISIBLE
            binding.participantVideoLoadingIndicator.visibility = GONE
            binding.participantBgLetterbox.visibility = visible(binding.participantStreamPreviewImage.visibility != VISIBLE)
            binding.participantStreamPreviewImage.visibility = GONE
            binding.participantStreamPreviewText.visibility = GONE
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

    /**
     * Pinch to zoom and drag to pan, only on the focused tile: in the grid a pinch would fight the
     * scroll and every tile's tap. Once a touch becomes a zoom gesture the rest of it is kept from
     * the tile, so it neither toggles the call controls nor opens the long-press sheet.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val zoomable = drawFits && lastFrame != null && binding.participantVideoStreamRenderer.visibility == VISIBLE
        if (!zoomable && !zoomGestureActive) return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // Touching the picture stops a double-tap zoom-out where it is.
            zoomOutAnimator?.cancel()
            zoomGestureActive = false
            downX = event.x
            downY = event.y
            lastX = event.x
            lastY = event.y
            binding.participantVideoStreamRenderer.setHoldLastFrame(true)
            // A zoomed picture owns one-finger drags; otherwise the screen behind it sees the drag
            // first and takes it, and the pan never starts.
            if (zoom > 1f) parent?.requestDisallowInterceptTouchEvent(true)
        }
        pinch.onTouchEvent(event)
        doubleTap.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> startZoomGesture(event)
            MotionEvent.ACTION_POINTER_UP -> {
                // The finger left on the screen carries on panning from where it is.
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
            }
            MotionEvent.ACTION_MOVE -> {
                if (zoom > 1f && hypot(event.x - downX, event.y - downY) > touchSlop) startZoomGesture(event)
                if (zoomGestureActive && event.pointerCount == 1) panBy(event.x - lastX, event.y - lastY)
                lastX = event.x
                lastY = event.y
            }
        }
        val ended = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        if (ended && zoomOutAnimator?.isRunning != true) binding.participantVideoStreamRenderer.setHoldLastFrame(false)
        if (!zoomGestureActive) return super.dispatchTouchEvent(event)
        if (ended) zoomGestureActive = false
        return true
    }

    private fun startZoomGesture(event: MotionEvent) {
        if (zoomGestureActive) return
        zoomGestureActive = true
        parent?.requestDisallowInterceptTouchEvent(true)
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    /**
     * The whole picture's on-screen size at the current zoom: fitted to the tile, then scaled. Null
     * until a frame has arrived and the tile has been laid out.
     */
    private fun zoomedPictureSize(): Pair<Float, Float>? {
        val frame = lastFrame ?: return null
        if (width <= 0 || height <= 0) return null
        val fitted = minOf(width.toFloat() / frame.x, height.toFloat() / frame.y) * zoom
        return frame.x * fitted to frame.y * fitted
    }

    /** Scales by [factor] keeping the picture point under ([focusX], [focusY]) where it is. */
    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val (oldWidth, oldHeight) = zoomedPictureSize() ?: return
        zoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val (newWidth, newHeight) = zoomedPictureSize() ?: return
        val offsetX = focusX - width / 2f
        val offsetY = focusY - height / 2f
        zoomCenterX += offsetX / oldWidth - offsetX / newWidth
        zoomCenterY += offsetY / oldHeight - offsetY / newHeight
        applyZoom()
    }

    private fun panBy(dx: Float, dy: Float) {
        if (zoom <= 1f) return
        val (pictureWidth, pictureHeight) = zoomedPictureSize() ?: return
        zoomCenterX -= dx / pictureWidth
        zoomCenterY -= dy / pictureHeight
        applyZoom()
    }

    /**
     * Hands the zoom to the renderer, which draws the part of the zoomed picture that lands on the
     * tile: it grows into the letterbox until it covers the tile. The centre is clamped here with
     * the same rule the renderer uses, so a drag past the edge does not build up slack.
     */
    private fun applyZoom() {
        val (pictureWidth, pictureHeight) = zoomedPictureSize() ?: return
        val fractionX = minOf(1f, width / pictureWidth)
        val fractionY = minOf(1f, height / pictureHeight)
        // The shown part never leaves the picture; an axis the picture does not fill stays centred.
        zoomCenterX = zoomCenterX.coerceIn(fractionX / 2f, 1f - fractionX / 2f)
        zoomCenterY = zoomCenterY.coerceIn(fractionY / 2f, 1f - fractionY / 2f)
        binding.participantVideoStreamRenderer.setFitZoom(zoom, zoomCenterX, zoomCenterY)
    }

    private fun animateZoomOut() {
        zoomOutAnimator?.cancel()
        zoomOutAnimator = ValueAnimator.ofFloat(zoom, 1f).apply {
            duration = ZOOM_OUT_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                zoom = it.animatedValue as Float
                applyZoom()
            }
            doOnEnd { binding.participantVideoStreamRenderer.setHoldLastFrame(false) }
            start()
        }
    }

    private fun resetZoom() {
        zoomOutAnimator?.cancel()
        binding.participantVideoStreamRenderer.setHoldLastFrame(false)
        zoom = 1f
        zoomCenterX = 0.5f
        zoomCenterY = 0.5f
    }

    private fun fitRendererTo(frame: Point?) {
        val params = binding.participantVideoStreamRenderer.layoutParams as? ConstraintLayout.LayoutParams ?: return
        val ratio = frame?.let { "${it.x}:${it.y}" }
        if (params.dimensionRatio == ratio) return
        params.dimensionRatio = ratio
        binding.participantVideoStreamRenderer.layoutParams = params
    }

    private fun resetStreamState() {
        binding.participantStreamPreviewImage.visibility = GONE
        binding.participantStreamPreviewText.visibility = GONE
        binding.participantStreamPreviewAdd.visibility = GONE
        binding.participantStreamPaused.visibility = GONE
        binding.participantStreamEnded.visibility = GONE
        binding.participantLiveIndicator.visibility = GONE
        binding.participantBgLetterbox.visibility = GONE
    }

    private fun configureQuality(data: ParticipantData?) {
        val quality = data?.streamQuality
        val shown = data?.isFullScreen == true && showStreamQuality && quality != null &&
            data.applicationStreamState in setOf(
                ParticipantData.ApplicationStreamState.ACTIVE,
                ParticipantData.ApplicationStreamState.PAUSED,
            )
        if (!shown || quality == null) {
            binding.participantFullscreenStreamqualContainer.fadeBy(false, FADE_DURATION_MS)
            return
        }
        binding.participantFullscreenStreamqualContainer.translationX = -callUiInsets.right.toFloat()
        binding.participantFullscreenStreamqualContainer.translationY = callUiInsets.top.toFloat()
        binding.participantFullscreenStreamqualIcon.visibility = visible(quality.isPremium)
        binding.participantFullscreenStreamqualInfo.text = listOfNotNull(
            quality.resolution.label(context).toString().takeIf(String::isNotBlank),
            quality.fps?.let { context.i18nFormat(R.string.stream_fps_option, "value" to it.value) },
        ).joinToString(" ")
        binding.participantFullscreenStreamqualInfo.visibility = visible(binding.participantFullscreenStreamqualInfo.text.isNotEmpty())
        binding.participantFullscreenStreamqualContainer.fadeBy(true, FADE_DURATION_MS)
        binding.participantFullscreenStreamqualLiveIndicator.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                context,
                if (quality.isPoorQuality) R.color.status_yellow_500 else R.color.white,
            ),
        )
        onStreamQualityIndicatorShown(quality.resolution, quality.fps)
    }

    private fun configureLabel(data: ParticipantData?) {
        val participant = data?.voiceParticipant ?: return
        binding.participantVideoLabel.text = participant.displayName
        binding.participantVideoLabel.setCompoundDrawablesWithIntrinsicBounds(
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
        if (labelInitialized) binding.participantVideoLabel.fadeBy(data.showLabel, FADE_DURATION_MS)
        else binding.participantVideoLabel.visibility = visible(data.showLabel)
        labelInitialized = true
    }

    private fun configureCutout() {
        val insetTop = displayCutout?.safeInsetTop ?: 0
        val set = ConstraintSet().apply { clone(this@VideoCallParticipantView) }
        // A top-row tile on a device with a notch moves the LIVE pill to the bottom
        // so it is not hidden by the cutout, unless the mute/deafen row already
        // occupies that corner.
        val moveToBottom = insetTop > 0 && isTopRow && binding.participantVoiceIndicators.visibility != VISIBLE
        set.connect(binding.participantLiveIndicator.id, ConstraintSet.RIGHT, ConstraintSet.PARENT_ID, ConstraintSet.RIGHT, dpToPixels(8))
        if (moveToBottom) {
            set.connect(binding.participantLiveIndicator.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, dpToPixels(8))
            set.clear(binding.participantLiveIndicator.id, ConstraintSet.TOP)
        } else {
            set.connect(binding.participantLiveIndicator.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, dpToPixels(8))
            set.clear(binding.participantLiveIndicator.id, ConstraintSet.BOTTOM)
        }
        set.applyTo(this)
    }

    override fun onDetachedFromWindow() {
        detachRenderer()
        labelInitialized = false
        super.onDetachedFromWindow()
    }

    private fun detachRenderer() {
        binding.participantVideoStreamRenderer.onFrameRenderedListener = null
        binding.participantVideoStreamRenderer.configure(null, null, null, false)
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
        const val MAX_ZOOM = 5f
        const val ZOOM_OUT_DURATION_MS = 200L
    }
}
