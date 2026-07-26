package com.discord.rlottie

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a Lottie animation through the native `dsti` rasteriser.
 *
 * Frames are decoded off the main thread into a rotating trio of bitmaps: one is
 * on screen, one holds the frame that has been decoded but not yet shown, and one
 * is handed back to the decoder for reuse. Playback stops by itself once no
 * attached view is left to draw the animation, so a recycled list row does not
 * keep a decoder thread busy.
 */
class RLottieDrawable : BitmapDrawable, Animatable {
    enum class PlaybackMode { LOOP, ONCE, FREEZE }

    private var widthPx = 0
    private var heightPx = 0
    private val metadata = IntArray(3)
    private var frameIntervalMs = DEFAULT_FRAME_INTERVAL_MS
    private var playbackMode = PlaybackMode.LOOP
    private var completedPlaybackCount = 0
    private var lastFrameTimeMs = 0L
    private var limitFps = false
    private var displayRefreshRate = DEFAULT_REFRESH_RATE
    private var currentFrame = 0

    @Volatile private var terminalFramePrepared = false
    @Volatile private var currentBitmap: Bitmap? = null
    @Volatile private var readyBitmap: Bitmap? = null
    @Volatile private var reusableBitmap: Bitmap? = null
    @Volatile private var framePending = false
    @Volatile private var cachePending = false
    @Volatile private var running = false
    @Volatile private var nativePtr = 0L

    private var allowDecodeSingleFrame = false
    private var singleFrameDecoded = false
    private var forceFrameSwap = false
    private var boundsDirty = true
    private val destinationBounds = Rect()

    /** Layer colour overrides staged on the caller thread. */
    private val stagedLayerColors = LinkedHashMap<String, Int>()

    /** Layer colour overrides handed to the decoder thread. */
    @Volatile private var pendingLayerColors: LinkedHashMap<String, Int>? = null

    /** Whole-palette replacement, applied once on the next decode. */
    @Volatile private var pendingReplacementColors: IntArray? = null

    private val parentViews = ArrayList<WeakReference<View>>(1)

    constructor(
        context: Context,
        rawRes: Int,
        name: String,
        width: Int,
        height: Int,
        displayRefreshRate: Float,
        decodeSingleFrame: Boolean,
        colorReplacement: IntArray?,
    ) : super() {
        initialize(width, height, displayRefreshRate)
        try {
            val json = context.resources.openRawResource(rawRes)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            nativePtr = createWithJson(json, name, metadata, colorReplacement)
            frameIntervalMs = frameInterval(limitFps = false)
            setAllowDecodeSingleFrame(decodeSingleFrame)
        } catch (error: Throwable) {
            Log.e(TAG, "Error constructing animation", error)
        }
    }

    constructor(
        file: File,
        width: Int,
        height: Int,
        precache: Boolean,
        limitFps: Boolean,
        displayRefreshRate: Float,
        colorReplacement: IntArray? = null,
    ) : super() {
        initialize(width, height, displayRefreshRate)
        this.limitFps = limitFps
        nativePtr = create(file.absolutePath, width, height, metadata, precache, colorReplacement, limitFps)
        if (nativePtr == 0L) {
            // A file the native decoder rejects will never decode; drop it so the
            // next request re-downloads instead of failing forever.
            file.delete()
            return
        }
        if (precache) ensureCacheQueue()
        if (this.limitFps && metadata[1] < FPS_LIMIT_THRESHOLD) this.limitFps = false
        frameIntervalMs = frameInterval(this.limitFps)
    }

    private fun initialize(width: Int, height: Int, refreshRate: Float) {
        widthPx = width
        heightPx = height
        displayRefreshRate = refreshRate
        paint.flags = Paint.FILTER_BITMAP_FLAG
    }

    private fun frameInterval(limitFps: Boolean): Int {
        val fps = metadata[1].coerceAtLeast(1)
        return max(if (limitFps) LIMITED_FRAME_INTERVAL_MS else DEFAULT_FRAME_INTERVAL_MS, 1000 / fps)
    }

    val frameCount: Int get() = metadata[0]

    // region playback control

    fun setPlaybackMode(mode: PlaybackMode) {
        // A one-shot animation that is already part way through must finish on the
        // frame it reaches, not freeze wherever the caller happened to switch modes.
        if (playbackMode == PlaybackMode.ONCE && mode == PlaybackMode.FREEZE && currentFrame != 0) return
        playbackMode = mode
    }

    fun setAllowDecodeSingleFrame(allow: Boolean) {
        allowDecodeSingleFrame = allow
        if (allow) scheduleNextFrame()
    }

    fun setProgress(progress: Float) {
        currentFrame = (metadata[0] * progress.coerceIn(0f, 1f)).toInt()
        terminalFramePrepared = false
        singleFrameDecoded = false
        if (!scheduleNextFrame()) forceFrameSwap = true
        invalidateSelf()
    }

    override fun start() {
        if (running || nativePtr == 0L) return
        // ONCE and FREEZE animations do not replay once they have finished.
        if (playbackMode != PlaybackMode.LOOP && completedPlaybackCount != 0) return
        running = true
        scheduleNextFrame()
        invalidateInternal()
    }

    override fun stop() {
        running = false
    }

    override fun isRunning() = running

    // endregion

    // region colour mutation

    /** Overrides the colour of a named Lottie layer on the next decoded frame. */
    fun setLayerColor(layerName: String, color: Int) {
        synchronized(stagedLayerColors) { stagedLayerColors[layerName] = color }
        forceFrameSwap = true
        if (!scheduleNextFrame()) invalidateSelf()
    }

    /** Replaces the animation palette; pairs of `from`/`to` colours, as the native side expects. */
    fun replaceColors(colors: IntArray) {
        pendingReplacementColors = colors.copyOf()
        forceFrameSwap = true
        if (!scheduleNextFrame()) invalidateSelf()
    }

    private fun stageColorUpdates() {
        synchronized(stagedLayerColors) {
            if (stagedLayerColors.isEmpty()) return
            val pending = pendingLayerColors ?: LinkedHashMap()
            pending.putAll(stagedLayerColors)
            stagedLayerColors.clear()
            pendingLayerColors = pending
        }
    }

    private fun applyColorUpdates(ptr: Long) {
        pendingLayerColors?.let { pending ->
            pendingLayerColors = null
            for ((layer, color) in pending) {
                runCatching { setLayerColor(ptr, layer, color) }
                    .onFailure { Log.e(TAG, "Error applying layer colour $layer", it) }
            }
        }
        pendingReplacementColors?.let { colors ->
            pendingReplacementColors = null
            runCatching { replaceColors(ptr, colors) }
                .onFailure { Log.e(TAG, "Error replacing colours", it) }
        }
    }

    // endregion

    // region parent view registry

    /**
     * Registers a view that draws this animation. The drawable keeps only weak
     * references, and stops itself once every registered view has gone away.
     */
    fun addParentView(view: View) {
        synchronized(parentViews) {
            if (parentViews.none { it.get() === view }) parentViews.add(WeakReference(view))
        }
    }

    fun removeParentView(view: View) {
        synchronized(parentViews) {
            parentViews.removeAll { it.get() == null || it.get() === view }
        }
    }

    private fun hasParentView(): Boolean {
        if (callback != null) return true
        synchronized(parentViews) {
            val iterator = parentViews.iterator()
            var alive = false
            while (iterator.hasNext()) {
                if (iterator.next().get() == null) iterator.remove() else alive = true
            }
            return alive
        }
    }

    private fun invalidateInternal() {
        synchronized(parentViews) {
            val iterator = parentViews.iterator()
            while (iterator.hasNext()) {
                val view = iterator.next().get()
                if (view == null) iterator.remove() else view.invalidate()
            }
        }
        if (callback != null) invalidateSelf()
    }

    // endregion

    // region frame pipeline

    private fun scheduleNextFrame(): Boolean {
        if (framePending || readyBitmap != null || nativePtr == 0L) return false
        if (!running && (!allowDecodeSingleFrame || singleFrameDecoded)) return false
        stageColorUpdates()
        framePending = true
        FRAME_EXECUTOR.execute(::decodeFrame)
        return true
    }

    private fun decodeFrame() {
        val ptr = nativePtr
        if (ptr == 0L) {
            MAIN_HANDLER.post(::onFrameUnavailable)
            return
        }
        var decoded = false
        try {
            val bitmap = reusableBitmap?.takeIf { !it.isRecycled }
                ?: Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            reusableBitmap = null
            applyColorUpdates(ptr)
            val result = getFrame(ptr, currentFrame, bitmap, widthPx, heightPx, bitmap.rowBytes, true)
            if (result == -1) {
                reusableBitmap = bitmap
            } else {
                decoded = true
                readyBitmap = bitmap
                // The native decoder reports through metadata[2] that no on-disk
                // frame cache exists yet; building it makes later playback cheap.
                if (metadata[2] != 0) {
                    metadata[2] = 0
                    MAIN_HANDLER.post(::requestCacheGeneration)
                }
                advanceFrame()
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Error decoding frame", error)
        }
        MAIN_HANDLER.post(if (decoded) ::onFrameDecoded else ::onFrameUnavailable)
    }

    private fun onFrameDecoded() {
        framePending = false
        singleFrameDecoded = true
        invalidateInternal()
        checkRunningTasks()
    }

    private fun onFrameUnavailable() {
        framePending = false
        checkRunningTasks()
    }

    /**
     * Releases resources once the animation is gone, and stops playback as soon as
     * no view is left drawing it.
     */
    private fun checkRunningTasks() {
        if (nativePtr == 0L) {
            recycleBitmaps()
            return
        }
        if (!hasParentView()) running = false
        scheduleNextFrame()
    }

    private fun advanceFrame() {
        val framesPerUpdate = if (limitFps) 2 else 1
        val next = currentFrame + framesPerUpdate
        when {
            next < metadata[0] -> if (playbackMode == PlaybackMode.FREEZE) {
                terminalFramePrepared = true
                completedPlaybackCount++
            } else {
                currentFrame = next
                terminalFramePrepared = false
            }
            playbackMode == PlaybackMode.LOOP -> {
                currentFrame = 0
                terminalFramePrepared = false
            }
            playbackMode == PlaybackMode.ONCE -> {
                currentFrame = 0
                terminalFramePrepared = true
                completedPlaybackCount++
            }
            else -> {
                terminalFramePrepared = true
                completedPlaybackCount++
            }
        }
    }

    // endregion

    // region frame cache

    private fun requestCacheGeneration() {
        if (nativePtr == 0L || cachePending) return
        // The queue only exists once some animation has asked to be pre-cached; a drawable built
        // with `precache = false` never rasterises a cache, matching the reference's null check on
        // its cache executor.
        val queue = cacheQueue ?: return
        cachePending = true
        queue.execute {
            val ptr = nativePtr
            if (cachePending && ptr != 0L) {
                runCatching { createCache(ptr, widthPx, heightPx) }
                    .onFailure { Log.e(TAG, "Error generating frame cache", it) }
            }
            MAIN_HANDLER.post {
                cachePending = false
                checkRunningTasks()
            }
        }
    }

    // endregion

    // region drawing

    override fun draw(canvas: Canvas) {
        if (nativePtr == 0L) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = abs(now - lastFrameTimeMs)
        // Below 60 Hz the frame budget is shortened slightly so a frame is never
        // held for two refreshes when it lands just short of the interval.
        val threshold = if (displayRefreshRate <= DEFAULT_REFRESH_RATE) {
            frameIntervalMs - LOW_REFRESH_RATE_TOLERANCE_MS
        } else {
            frameIntervalMs
        }

        if (running) {
            if (currentBitmap == null && readyBitmap == null) {
                scheduleNextFrame()
            } else if (readyBitmap != null && (currentBitmap == null || elapsed >= threshold)) {
                swapBitmaps()
                if (terminalFramePrepared) running = false
                singleFrameDecoded = true
                lastFrameTimeMs = if (displayRefreshRate > DEFAULT_REFRESH_RATE) {
                    now - min(HIGH_REFRESH_RATE_CATCHUP_MS, max(0L, elapsed - threshold))
                } else {
                    now
                }
                scheduleNextFrame()
            }
        } else if (readyBitmap != null && (forceFrameSwap || (allowDecodeSingleFrame && elapsed >= threshold))) {
            swapBitmaps()
            // A paused drawable has now shown the frame it was waiting for, so no further decode is
            // scheduled — unless the swap was forced by a colour/progress change, which must show
            // the frame after it too.
            singleFrameDecoded = true
            if (forceFrameSwap) {
                singleFrameDecoded = false
                forceFrameSwap = false
            }
            lastFrameTimeMs = now
        }

        val bitmap = currentBitmap ?: return
        if (boundsDirty) {
            destinationBounds.set(bounds)
            boundsDirty = false
        }
        val save = canvas.save()
        canvas.translate(destinationBounds.left.toFloat(), destinationBounds.top.toFloat())
        canvas.scale(
            destinationBounds.width().toFloat() / widthPx,
            destinationBounds.height().toFloat() / heightPx,
        )
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        canvas.restoreToCount(save)
        if (running) invalidateInternal()
    }

    private fun swapBitmaps() {
        reusableBitmap = currentBitmap
        currentBitmap = readyBitmap
        readyBitmap = null
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        boundsDirty = true
    }

    override fun getIntrinsicWidth() = widthPx
    override fun getIntrinsicHeight() = heightPx
    override fun getMinimumWidth() = widthPx
    override fun getMinimumHeight() = heightPx
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    // endregion

    /**
     * Frees the native animation and every bitmap it owns. The drawable renders
     * nothing afterwards, so callers must drop their reference to it.
     */
    fun destroy() {
        val ptr = nativePtr
        nativePtr = 0L
        running = false
        cachePending = false
        synchronized(parentViews) { parentViews.clear() }
        if (ptr != 0L) {
            runCatching { destroy(ptr) }.onFailure { Log.e(TAG, "Error destroying animation", it) }
        }
        if (framePending) return // the in-flight decode recycles through checkRunningTasks
        recycleBitmaps()
    }

    private fun recycleBitmaps() {
        currentBitmap?.recycle()
        readyBitmap?.recycle()
        reusableBitmap?.recycle()
        currentBitmap = null
        readyBitmap = null
        reusableBitmap = null
    }

    companion object {
        private const val TAG = "RLottieDrawable"
        private const val DEFAULT_FRAME_INTERVAL_MS = 16
        private const val LIMITED_FRAME_INTERVAL_MS = 33
        private const val LOW_REFRESH_RATE_TOLERANCE_MS = 6
        private const val HIGH_REFRESH_RATE_CATCHUP_MS = 16L
        private const val DEFAULT_REFRESH_RATE = 60f
        private const val FPS_LIMIT_THRESHOLD = 60

        private val MAIN_HANDLER = Handler(Looper.getMainLooper())
        private val FRAME_EXECUTOR = Executors.newCachedThreadPool()

        /**
         * Cache generation is serialised across every animation: it is a whole-file
         * rasterisation and running several at once starves the frame decoders.
         */
        @Volatile private var cacheQueue: ExecutorService? = null

        private fun ensureCacheQueue(): ExecutorService = cacheQueue ?: synchronized(this) {
            cacheQueue ?: Executors.newSingleThreadExecutor().also { cacheQueue = it }
        }

        external fun create(
            path: String,
            width: Int,
            height: Int,
            metadata: IntArray,
            precache: Boolean,
            colors: IntArray?,
            limitFps: Boolean,
        ): Long

        external fun createWithJson(
            json: String,
            name: String,
            metadata: IntArray,
            colors: IntArray?,
        ): Long

        external fun createCache(ptr: Long, width: Int, height: Int)

        external fun destroy(ptr: Long)

        external fun getFrame(
            ptr: Long,
            frame: Int,
            bitmap: Bitmap,
            width: Int,
            height: Int,
            rowBytes: Int,
            clear: Boolean,
        ): Int

        external fun replaceColors(ptr: Long, colors: IntArray)

        external fun setLayerColor(ptr: Long, layer: String, color: Int)
    }
}
