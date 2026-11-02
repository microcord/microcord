package com.discord.views.calls

import android.content.Context
import android.graphics.Point
import android.opengl.Matrix
import android.util.AttributeSet
import androidx.annotation.MainThread
import co.discord.media_engine.VideoOutputSink
import co.discord.media_engine.VideoStreamRenderer
import com.discord.stores.StoreStream
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.VideoFrame
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.subjects.BehaviorSubject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal fun rotatedFrameSize(width: Int, height: Int, rotation: Int) =
    if (rotation == 0 || rotation == 180 || rotation == -180) width to height else height to width

/**
 * Where to draw a fitted, zoomed picture inside a viewport, and the texture transform that shows it.
 *
 * WebRTC's renderer always fills its viewport, cropping the frame to the viewport's shape. To fit
 * instead, the picture is drawn into a sub-viewport of its own shape — the part of it that lands on
 * screen — and [scaleX]/[offsetX] (and Y) undo that crop and select the zoomed window. The texture
 * transform takes viewport coordinates, which run bottom-up.
 */
internal data class ZoomedDraw(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val scaleX: Float,
    val scaleY: Float,
    val offsetX: Float,
    val offsetY: Float,
)

/**
 * @param frameWidth the frame as shown, after rotation.
 * @param zoom 1 shows the whole picture fitted, letterboxed.
 * @param centerX the zoomed window's centre, as a fraction of the picture as seen, top-down.
 */
internal fun zoomedDraw(
    frameWidth: Int,
    frameHeight: Int,
    viewportX: Int,
    viewportY: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    zoom: Float,
    centerX: Float,
    centerY: Float,
): ZoomedDraw {
    val frameAspect = frameWidth.toFloat() / frameHeight
    val viewportAspect = viewportWidth.toFloat() / viewportHeight
    // The crop the renderer already applies to fill the viewport.
    val fillX = if (frameAspect > viewportAspect) viewportAspect / frameAspect else 1f
    val fillY = if (frameAspect > viewportAspect) 1f else frameAspect / viewportAspect

    val scale = minOf(viewportWidth.toFloat() / frameWidth, viewportHeight.toFloat() / frameHeight) * zoom
    val pictureWidth = frameWidth * scale
    val pictureHeight = frameHeight * scale
    val shownWidth = minOf(pictureWidth, viewportWidth.toFloat())
    val shownHeight = minOf(pictureHeight, viewportHeight.toFloat())
    val fractionX = shownWidth / pictureWidth
    val fractionY = shownHeight / pictureHeight
    val windowX = centerX.coerceIn(fractionX / 2f, 1f - fractionX / 2f)
    val windowY = 1f - centerY.coerceIn(fractionY / 2f, 1f - fractionY / 2f)

    val scaleX = fractionX / fillX
    val scaleY = fractionY / fillY
    return ZoomedDraw(
        x = viewportX + ((viewportWidth - shownWidth) / 2f).roundToInt(),
        y = viewportY + ((viewportHeight - shownHeight) / 2f).roundToInt(),
        width = shownWidth.roundToInt(),
        height = shownHeight.roundToInt(),
        scaleX = scaleX,
        scaleY = scaleY,
        offsetX = 0.5f + (windowX - 0.5f) / fillX - scaleX / 2f,
        offsetY = 0.5f + (windowY - 0.5f) / fillY - scaleY / 2f,
    )
}

class AppVideoStreamRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : VideoStreamRenderer(context, attrs) {
    var videoOutputSink: VideoOutputSink? = null
        set(value) {
            if (field === value) return
            val currentStreamId = streamId
            if (currentStreamId != null) attachToStream(field, null)
            field = value
            if (currentStreamId != null) attachToStream(value, currentStreamId.toString(), rendererEvents)
        }
    val currentFrameResolutionSubject: BehaviorSubject<Point> = BehaviorSubject.create()
    var onFrameRenderedListener: ((Point) -> Unit)? = null
    private val sizeChanged = BehaviorSubject.create(Unit)
    private var resolutionSubscription: Subscription? = null
    private var streamId: Int? = null
    private var isOverlay = false
    private var scalingType = RendererCommon.ScalingType.SCALE_ASPECT_BALANCED
    private var mismatchScalingType = RendererCommon.ScalingType.SCALE_ASPECT_BALANCED
    private var rendererEvents: RendererEvents? = null

    /** Written on the main thread, read on the GL thread. Null draws the way WebRTC does. */
    @Volatile private var fitZoom: FitZoom? = null

    /**
     * The latest frame's size as shown. The drawer is only told the size after WebRTC's fill crop,
     * which has already lost the picture's shape.
     */
    @Volatile private var shownFrameSize: Point? = null

    @Volatile private var holdLastFrame = false
    private val heldFrameLock = Any()
    private var heldFrame: VideoFrame? = null

    @MainThread
    fun configure(
        newStreamId: Int?,
        scalingType: RendererCommon.ScalingType?,
        scalingTypeMismatchOrientation: RendererCommon.ScalingType?,
        isVideoMirrored: Boolean,
    ) {
        if (newStreamId == null) {
            detachStream()
            streamId = null
            return
        }
        if (newStreamId != streamId) {
            detachStream()
            val events = RendererEvents()
            rendererEvents = events
            resolutionSubscription = sizeChanged
                .switchMap { events.resolutions.debounce(50, TimeUnit.MILLISECONDS) }
                .map { rotatedFrameSize(it.width, it.height, it.rotation) }
                .map { Point(it.first, it.second) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    {
                        currentFrameResolutionSubject.onNext(it)
                        onFrameRenderedListener?.invoke(it)
                    },
                    { error -> android.util.Log.e(javaClass.simpleName, "Frame resolution stream failed", error) },
                )
            previousRenderers[newStreamId]?.takeIf { it !== this }?.detachStream()
            previousRenderers[newStreamId] = this
            attachToStream(videoOutputSink ?: StoreStream.getMediaEngine(), newStreamId.toString(), events)
            streamId = newStreamId
        }
        val scale = scalingType ?: this.scalingType
        val mismatchScale = scalingTypeMismatchOrientation ?: this.mismatchScalingType
        setMirror(isVideoMirrored)
        setZOrderMediaOverlay(isOverlay)
        if (scale != this.scalingType || mismatchScale != this.mismatchScalingType) {
            setScalingType(scale, mismatchScale)
            this.scalingType = scale
            this.mismatchScalingType = mismatchScale
        }
    }

    @MainThread
    fun c(
        newStreamId: Int?,
        scalingType: RendererCommon.ScalingType?,
        scalingTypeMismatchOrientation: RendererCommon.ScalingType?,
        isVideoMirrored: Boolean,
    ) = configure(newStreamId, scalingType, scalingTypeMismatchOrientation, isVideoMirrored)

    @MainThread
    fun detachStream() {
        resolutionSubscription?.unsubscribe()
        resolutionSubscription = null
        rendererEvents = null
        shownFrameSize = null
        setHoldLastFrame(false)
        previousRenderers.remove(streamId)
        currentFrameResolutionSubject.onNext(null)
        attachToStream(videoOutputSink ?: StoreStream.getMediaEngine(), null)
    }

    @MainThread
    fun b() = detachStream()

    fun setIsOverlay(value: Boolean) {
        isOverlay = value
    }

    fun setMatchVideoOrientation(value: Boolean) = Unit

    /**
     * Fits the whole picture inside this view, letterboxed, and zooms it [zoom] times around
     * ([centerX], [centerY]), fractions of the picture as seen. Null restores WebRTC's own fill.
     *
     * It all happens in the draw, so the view keeps its size: resizing a SurfaceView on every step
     * of a pinch reallocates its surface and stutters. Takes effect from the next frame.
     */
    fun setFitZoom(zoom: Float?, centerX: Float = 0.5f, centerY: Float = 0.5f) {
        val next = zoom?.let { FitZoom(it, centerX, centerY) }
        if (next == fitZoom) return
        fitZoom = next
        redrawHeldFrame()
    }

    /**
     * While [hold] is set the latest frame is kept and redrawn on every zoom change, so a pinch moves
     * at touch rate rather than at the stream's frame rate, which for a still screen share can be a
     * few frames a second.
     *
     * Only for the length of a gesture: a decoder with a single output buffer cannot deliver its next
     * frame until the held one is released, so holding for longer would freeze the video.
     */
    fun setHoldLastFrame(hold: Boolean) {
        holdLastFrame = hold
        if (!hold) synchronized(heldFrameLock) {
            heldFrame?.release()
            heldFrame = null
        }
    }

    private fun redrawHeldFrame() {
        val frame = synchronized(heldFrameLock) { heldFrame?.also { it.retain() } } ?: return
        try {
            super.onFrame(frame)
        } finally {
            frame.release()
        }
    }

    override fun createDrawer(): RendererCommon.GlDrawer = FitZoomDrawer()

    override fun onFrame(frame: VideoFrame) {
        val shown = shownFrameSize
        if (shown == null || shown.x != frame.rotatedWidth || shown.y != frame.rotatedHeight) {
            shownFrameSize = Point(frame.rotatedWidth, frame.rotatedHeight)
        }
        if (holdLastFrame) synchronized(heldFrameLock) {
            frame.retain()
            heldFrame?.release()
            heldFrame = frame
        }
        super.onFrame(frame)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeChanged.onNext(Unit)
        clearImage()
    }

    /** WebRTC's drawer, redirected into the fitted, zoomed window whenever [fitZoom] is set. */
    private inner class FitZoomDrawer : RendererCommon.GlDrawer {
        private val delegate = GlRectDrawer()
        private val window = FloatArray(16)
        private val adjusted = FloatArray(16)

        override fun drawOes(texture: Int, texMatrix: FloatArray, frameWidth: Int, frameHeight: Int, x: Int, y: Int, width: Int, height: Int) =
            draw(texMatrix, frameWidth, frameHeight, x, y, width, height) { matrix, dx, dy, dw, dh ->
                delegate.drawOes(texture, matrix, frameWidth, frameHeight, dx, dy, dw, dh)
            }

        override fun drawRgb(texture: Int, texMatrix: FloatArray, frameWidth: Int, frameHeight: Int, x: Int, y: Int, width: Int, height: Int) =
            draw(texMatrix, frameWidth, frameHeight, x, y, width, height) { matrix, dx, dy, dw, dh ->
                delegate.drawRgb(texture, matrix, frameWidth, frameHeight, dx, dy, dw, dh)
            }

        override fun drawYuv(textures: IntArray, texMatrix: FloatArray, frameWidth: Int, frameHeight: Int, x: Int, y: Int, width: Int, height: Int) =
            draw(texMatrix, frameWidth, frameHeight, x, y, width, height) { matrix, dx, dy, dw, dh ->
                delegate.drawYuv(textures, matrix, frameWidth, frameHeight, dx, dy, dw, dh)
            }

        override fun release() = delegate.release()

        private inline fun draw(
            texMatrix: FloatArray,
            frameWidth: Int,
            frameHeight: Int,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            drawInto: (FloatArray, Int, Int, Int, Int) -> Unit,
        ) {
            val zoom = fitZoom
            val frame = shownFrameSize
            if (zoom == null || frame == null || frame.x <= 0 || frame.y <= 0 || width <= 0 || height <= 0) {
                return drawInto(texMatrix, x, y, width, height)
            }
            val draw = zoomedDraw(frame.x, frame.y, x, y, width, height, zoom.zoom, zoom.centerX, zoom.centerY)
            Matrix.setIdentityM(window, 0)
            Matrix.translateM(window, 0, draw.offsetX, draw.offsetY, 0f)
            Matrix.scaleM(window, 0, draw.scaleX, draw.scaleY, 1f)
            // The window transform runs first, on viewport coordinates, then WebRTC's own.
            Matrix.multiplyMM(adjusted, 0, texMatrix, 0, window, 0)
            drawInto(adjusted, draw.x, draw.y, draw.width, draw.height)
        }
    }

    private class RendererEvents : RendererCommon.RendererEvents {
        val resolutions: BehaviorSubject<Resolution> = BehaviorSubject.create()
        override fun onFirstFrameRendered() = Unit
        override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
            resolutions.onNext(Resolution(width, height, rotation))
        }
    }

    private data class FitZoom(val zoom: Float, val centerX: Float, val centerY: Float)

    private data class Resolution(val width: Int, val height: Int, val rotation: Int)

    private companion object {
        val previousRenderers = hashMapOf<Int, AppVideoStreamRenderer>()
    }
}
