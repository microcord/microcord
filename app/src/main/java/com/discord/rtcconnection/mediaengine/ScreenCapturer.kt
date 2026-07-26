package com.discord.rtcconnection.mediaengine

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import co.discord.media_engine.NativeCapturerObserver
import co.discord.media_engine.SoundshareAudioSource
import com.discord.app.AppLog
import com.discord.utilities.display.getScreenSize
import java.util.concurrent.TimeUnit
import org.webrtc.CapturerObserver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.TimestampAligner
import org.webrtc.VideoFrame
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers

/**
 * Screen capture for Go Live: WebRTC's `ScreenCapturerAndroid` plus the three things Android's
 * media projection does not give you.
 *
 * 1. **Rotation and resize.** The projection keeps producing frames at the original size when the
 *    device rotates or enters split screen, so every frame re-reads the screen size and, when it
 *    changed, re-derives a capture format. The derived size is snapped to a multiple of 16 because
 *    hardware H.264 encoders reject or pad odd macroblock counts.
 * 2. **Keepalive frames.** A still screen produces no frames at all, and a decoder that receives
 *    nothing shows a frozen or black tile to anyone who joins late. A timer at roughly twice the
 *    frame interval asks the native observer to repeat the last frame.
 * 3. **Application audio.** On API 29+ the projection can also capture playback audio, which is fed
 *    to the native engine through [SoundshareAudioSource].
 *
 * Reference: `b.a.q.m0.b` (`ScreenCapturer`).
 */
internal class ScreenCapturer(
    mediaProjectionPermissionResultData: Intent,
    mediaProjectionCallback: MediaProjection.Callback,
    private val thumbnailEmitter: ThumbnailEmitter?,
) : ScreenCapturerAndroid(mediaProjectionPermissionResultData, mediaProjectionCallback) {

    val soundshareAudioSource = SoundshareAudioSource()

    private lateinit var nativeObserver: NativeCapturerObserver
    private lateinit var surfaceTextureHelper: SurfaceTextureHelper
    private lateinit var appContext: Context

    private var frameRate: Int = 0
    private var keepaliveIntervalNs: Int = 0
    private var lastFrameTimestampNs: Long? = null

    private val lastMeasuredSize = Rect()
    private val currentScreenSize = Rect()
    private val captureSize = Point()
    private var requestedLongestSide: Int = 0

    private var keepaliveSubscription: Subscription? = null

    @Synchronized
    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        applicationContext: Context,
        capturerObserver: CapturerObserver,
    ) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.appContext = applicationContext
        this.nativeObserver = capturerObserver as NativeCapturerObserver
        super.initialize(surfaceTextureHelper, applicationContext, capturerObserver)
    }

    @Synchronized
    override fun startCapture(width: Int, height: Int, framerate: Int) {
        super.startCapture(width, height, framerate)
        requestedLongestSide = maxOf(width, height)
        setFrameRate(framerate)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createPlaybackCaptureRecord()?.let(soundshareAudioSource::startRecording)
        }
        scheduleKeepalive()
    }

    @Synchronized
    override fun stopCapture() {
        super.stopCapture()
        soundshareAudioSource.stopRecording()
        frameRate = 0
        lastFrameTimestampNs = null
    }

    @Synchronized
    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        applyCaptureSize(lastMeasuredSize, maxOf(width, height))
        setFrameRate(framerate)
    }

    override fun createVirtualDisplay() {
        // The projection can be revoked between the permission grant and the display creation; that
        // must degrade to "no stream", not to a crash inside a capture callback.
        try {
            super.createVirtualDisplay()
        } catch (t: Throwable) {
            AppLog.e(TAG, "error in createVirtualDisplay", t)
        }
    }

    override fun onFrame(frame: VideoFrame) {
        currentScreenSize.set(getScreenSize(appContext))
        if (currentScreenSize != lastMeasuredSize) {
            lastMeasuredSize.set(currentScreenSize)
            applyCaptureSize(currentScreenSize, requestedLongestSide)
        }
        lastFrameTimestampNs = frame.timestampNs
        thumbnailEmitter?.onVideoFrame(frame)
        super.onFrame(frame)
    }

    override fun dispose() {
        stopCapture()
        thumbnailEmitter?.release()
        soundshareAudioSource.release()
        super.dispose()
    }

    /**
     * Scales [measuredSize] so its longest side is [longestSide], then rounds each dimension to the
     * nearest multiple of 16.
     */
    @Synchronized
    private fun applyCaptureSize(measuredSize: Rect, longestSide: Int) {
        val longest = maxOf(measuredSize.width(), measuredSize.height())
        if (longest <= 0) return
        val scale = longestSide.toFloat() / longest
        captureSize.set(
            (measuredSize.width() * scale).toInt(),
            (measuredSize.height() * scale).toInt(),
        )
        captureSize.set(captureSize.x.roundToMacroblock(), captureSize.y.roundToMacroblock())
        super.changeCaptureFormat(captureSize.x, captureSize.y, frameRate)
    }

    private fun Int.roundToMacroblock(): Int {
        val remainder = this % MACROBLOCK
        return if (remainder >= MACROBLOCK / 2) this + MACROBLOCK - remainder else this - remainder
    }

    /**
     * Keepalive fires at twice the frame interval, floored at 10 fps worth of period, so a very low
     * capture rate still repeats often enough for a late joiner to see something.
     */
    private fun setFrameRate(framerate: Int) {
        if (framerate <= 0) {
            frameRate = 0
            keepaliveIntervalNs = 0
        } else {
            frameRate = framerate
            keepaliveIntervalNs = NANOS_PER_SECOND / maxOf(MIN_KEEPALIVE_FPS, framerate / 2)
        }
    }

    private fun scheduleKeepalive() {
        if (frameRate <= 0) return
        keepaliveSubscription?.unsubscribe()
        // Runs on the capture thread's looper: `repeatLastFrame` touches the SurfaceTextureHelper.
        keepaliveSubscription = Observable
            .timer(keepaliveIntervalNs / NANOS_PER_MILLI, TimeUnit.MILLISECONDS)
            .observeOn(AndroidSchedulers.from(surfaceTextureHelper.handler.looper))
            .subscribe(
                { onKeepaliveTick() },
                { error -> AppLog.e(TAG, "screen share keepalive failed", error) },
            )
    }

    private fun onKeepaliveTick() {
        if (frameRate <= 0) return
        val lastFrame = lastFrameTimestampNs
        if (lastFrame != null && TimestampAligner.getRtcTimeNanos() - lastFrame > keepaliveIntervalNs) {
            nativeObserver.repeatLastFrame()
        }
        scheduleKeepalive()
    }

    /**
     * Captures what the device is playing, so a shared video carries its own soundtrack.
     *
     * `MEDIA`, `GAME` and `UNKNOWN` usages only: `VOICE_COMMUNICATION` is deliberately excluded so
     * the call being streamed is not fed back into itself.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createPlaybackCaptureRecord(): AudioRecord? {
        val projection = mediaProjection ?: return null
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .build()
        return try {
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(PLAYBACK_CAPTURE_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: SecurityException) {
            AppLog.w(TAG, "Failed to record audio", e)
            null
        }
    }

    private companion object {
        private const val TAG = "ScreenCapturer"
        private const val MACROBLOCK = 16
        private const val MIN_KEEPALIVE_FPS = 10
        private const val NANOS_PER_SECOND = 1_000_000_000
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val PLAYBACK_CAPTURE_SAMPLE_RATE = 44_100
    }
}
