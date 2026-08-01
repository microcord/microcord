package com.discord.views.calls

import android.content.Context
import android.graphics.Point
import android.util.AttributeSet
import androidx.annotation.MainThread
import co.discord.media_engine.VideoOutputSink
import co.discord.media_engine.VideoStreamRenderer
import com.discord.stores.StoreStream
import org.webrtc.RendererCommon
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.subjects.BehaviorSubject
import java.util.concurrent.TimeUnit

internal fun rotatedFrameSize(width: Int, height: Int, rotation: Int) =
    if (rotation == 0 || rotation == 180 || rotation == -180) width to height else height to width

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeChanged.onNext(Unit)
        clearImage()
    }

    private class RendererEvents : RendererCommon.RendererEvents {
        val resolutions: BehaviorSubject<Resolution> = BehaviorSubject.create()
        override fun onFirstFrameRendered() = Unit
        override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
            resolutions.onNext(Resolution(width, height, rotation))
        }
    }

    private data class Resolution(val width: Int, val height: Int, val rotation: Int)

    private companion object {
        val previousRenderers = hashMapOf<Int, AppVideoStreamRenderer>()
    }
}
