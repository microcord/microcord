package com.discord.rlottie

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

class RLottieDrawable : BitmapDrawable, Animatable {
    enum class PlaybackMode { LOOP, ONCE, FREEZE }

    private var widthPx = 0
    private var heightPx = 0
    private val metadata = IntArray(3)
    private var frameIntervalMs = 16
    private var playbackMode = PlaybackMode.LOOP
    private var completedPlaybackCount = 0
    private var lastFrameTimeMs = 0L
    @Volatile private var terminalFramePrepared = false
    @Volatile private var currentBitmap: Bitmap? = null
    @Volatile private var readyBitmap: Bitmap? = null
    @Volatile private var reusableBitmap: Bitmap? = null
    @Volatile private var framePending = false
    private var allowDecodeSingleFrame = false
    private var singleFrameDecoded = false
    private var forceFrameSwap = false
    private var currentFrame = 0
    private var limitFps = false
    private var displayRefreshRate = 60f
    @Volatile private var running = false
    @Volatile private var nativePtr = 0L
    private var boundsDirty = true
    private val destinationBounds = Rect()

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
            val json = context.resources.openRawResource(rawRes).bufferedReader(Charsets.UTF_8).use { it.readText() }
            nativePtr = Companion.createWithJson(json, name, metadata, colorReplacement)
            frameIntervalMs = max(16, 1000 / metadata[1].coerceAtLeast(1))
            setAllowDecodeSingleFrame(decodeSingleFrame)
        } catch (error: Throwable) {
            Log.e("RLottieDrawable", "Error Constructing", error)
        }
    }

    constructor(
        file: File,
        width: Int,
        height: Int,
        precache: Boolean,
        limitFps: Boolean,
        displayRefreshRate: Float,
        @Suppress("UNUSED_PARAMETER") ignoredColors: IntArray?,
        @Suppress("UNUSED_PARAMETER") ignoredFinalInt: Int,
    ) : super() {
        initialize(width, height, displayRefreshRate)
        this.limitFps = limitFps
        nativePtr = Companion.create(file.absolutePath, width, height, metadata, precache, null, limitFps)
        if (nativePtr == 0L) file.delete()
        if (this.limitFps && metadata[1] < 60) this.limitFps = false
        frameIntervalMs = max(if (this.limitFps) 33 else 16, 1000 / metadata[1].coerceAtLeast(1))
    }

    private fun initialize(width: Int, height: Int, refreshRate: Float) {
        widthPx = width
        heightPx = height
        displayRefreshRate = refreshRate
        paint.flags = Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        if (playbackMode == PlaybackMode.ONCE && mode == PlaybackMode.FREEZE && currentFrame != 0) return
        playbackMode = mode
    }

    fun setAllowDecodeSingleFrame(allow: Boolean) {
        allowDecodeSingleFrame = allow
        if (allow) scheduleFrame()
    }

    fun setProgress(progress: Float) {
        currentFrame = (metadata[0] * progress.coerceIn(0f, 1f)).toInt()
        terminalFramePrepared = false
        singleFrameDecoded = false
        if (!scheduleFrame()) forceFrameSwap = true
        invalidateSelf()
    }

    override fun start() {
        if (running) return
        if ((playbackMode == PlaybackMode.ONCE || playbackMode == PlaybackMode.FREEZE) &&
            completedPlaybackCount != 0
        ) return
        running = true
        scheduleFrame()
        invalidateSelf()
    }

    override fun stop() {
        running = false
    }

    override fun isRunning() = running

    @Synchronized
    private fun scheduleFrame(): Boolean {
        if (framePending || readyBitmap != null || nativePtr == 0L ||
            (!running && (!allowDecodeSingleFrame || singleFrameDecoded))
        ) return false
        framePending = true
        FRAME_EXECUTOR.execute(::decodeFrame)
        return true
    }

    private fun decodeFrame() {
        try {
            if (nativePtr == 0L) return
            val bitmap = reusableBitmap ?: Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            reusableBitmap = null
            val result = Companion.getFrame(
                nativePtr,
                currentFrame,
                bitmap,
                widthPx,
                heightPx,
                bitmap.rowBytes,
                true,
            )
            if (result == -1) return
            readyBitmap = bitmap
            advanceFrame()
        } catch (error: Throwable) {
            Log.e("RLottieDrawable", "Error decoding frame", error)
        } finally {
            MAIN_HANDLER.post {
                framePending = false
                singleFrameDecoded = true
                invalidateSelf()
            }
        }
    }

    private fun advanceFrame() {
        if (playbackMode == PlaybackMode.FREEZE) {
            terminalFramePrepared = true
            completedPlaybackCount++
            return
        }
        currentFrame += if (limitFps) 1 else 2
        if (currentFrame >= metadata[0]) {
            currentFrame = 0
            if (playbackMode == PlaybackMode.ONCE) {
                terminalFramePrepared = true
                completedPlaybackCount++
            }
        }
    }

    override fun draw(canvas: Canvas) {
        if (nativePtr == 0L) return
        val now = SystemClock.elapsedRealtime()
        val threshold = frameIntervalMs - if (displayRefreshRate <= 60f) 6 else 0
        if (readyBitmap != null &&
            (currentBitmap == null || forceFrameSwap || now - lastFrameTimeMs >= threshold)
        ) {
            reusableBitmap = currentBitmap
            currentBitmap = readyBitmap
            readyBitmap = null
            forceFrameSwap = false
            lastFrameTimeMs = now - if (displayRefreshRate > 60f) {
                minOf(16L, maxOf(0L, now - lastFrameTimeMs - threshold))
            } else {
                0L
            }
            if (terminalFramePrepared) running = false
        }
        if (running) scheduleFrame()
        val bitmap = currentBitmap ?: return
        val bounds = bounds
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
        if (running) invalidateSelf()
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

    companion object {
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())
        private val FRAME_EXECUTOR = Executors.newCachedThreadPool()

        external fun create(
            path: String,
            width: Int,
            height: Int,
            metadata: IntArray,
            precache: Boolean,
            colors: IntArray?,
            limitFps: Boolean,
        ): Long

        external fun createCache(ptr: Long, width: Int, height: Int)
        external fun createWithJson(
            json: String,
            name: String,
            metadata: IntArray,
            colors: IntArray?,
        ): Long
        external fun getFrame(
            ptr: Long,
            frame: Int,
            bitmap: Bitmap,
            width: Int,
            height: Int,
            rowBytes: Int,
            force: Boolean,
        ): Int
        external fun replaceColors(ptr: Long, colors: IntArray)
        external fun setLayerColor(ptr: Long, layer: String, color: Int)
    }
}
