package com.discord.rtcconnection.mediaengine

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES20
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import org.webrtc.GlRectDrawer
import org.webrtc.GlTextureFrameBuffer
import org.webrtc.GlUtil
import org.webrtc.JniCommon
import org.webrtc.VideoFrame
import org.webrtc.VideoFrameDrawer

/**
 * Renders an occasional still out of a live capture, for the Go Live stream preview.
 *
 * It piggybacks on the capture pipeline rather than opening a second one: the frames are already on
 * the GL thread with a valid context, so a thumbnail costs one extra framebuffer blit and a
 * `glReadPixels` instead of a whole capture session.
 *
 * Two independent throttles apply, and both must pass:
 * - [initialDelayMs] after construction, so a stream that is torn down immediately never uploads a
 *   preview of a half-composited screen;
 * - [periodMs] between thumbnails.
 *
 * The output buffer is *native* memory ([JniCommon.nativeAllocateByteBuffer]) because
 * `glReadPixels` needs a direct buffer; [release] must be called or it leaks outside the Java heap.
 *
 * Reference: `com.discord.rtcconnection.mediaengine.ThumbnailEmitter`.
 */
class ThumbnailEmitter(
    private val width: Int,
    private val height: Int,
    private val periodMs: Long,
    private val initialDelayMs: Long = 0L,
    private val clock: Clock = ClockFactory.get(),
    private val onNextThumbnail: (Bitmap) -> Unit,
) {

    /**
     * Timestamp of the last emitted frame, seeded one full period in the past so the very first
     * frame after [initialDelayMs] qualifies.
     */
    private var lastTimestampNs: Long = -(periodMs * NANOS_PER_MILLI)

    private val outputByteBuffer: ByteBuffer =
        JniCommon.nativeAllocateByteBuffer(width * height * BYTES_PER_PIXEL)

    private val rectDrawer = GlRectDrawer()
    private val frameDrawer = VideoFrameDrawer()
    private val initializationTimeMs: Long = clock.currentTimeMillis()

    /** GL renders bottom-up; this flips vertically so the bitmap is the right way round. */
    private val renderMatrix = Matrix().apply {
        preTranslate(0.5f, 0.5f)
        preScale(1f, -1f)
        preTranslate(-0.5f, -0.5f)
    }

    @Synchronized
    fun onVideoFrame(frame: VideoFrame) {
        if (clock.currentTimeMillis() - initializationTimeMs <= initialDelayMs) return
        if (frame.timestampNs - lastTimestampNs <= periodMs * NANOS_PER_MILLI) return
        lastTimestampNs = frame.timestampNs
        onNextThumbnail(createThumbnail(frame))
    }

    @Synchronized
    fun release() {
        frameDrawer.release()
        rectDrawer.release()
        JniCommon.nativeFreeByteBuffer(outputByteBuffer)
        // Guarantees no further frame can pass the period check even if a capture callback races us.
        lastTimestampNs = Long.MAX_VALUE
    }

    /**
     * Draws [frame] letterboxed into a [width] x [height] framebuffer and copies it out as a bitmap.
     *
     * Letterboxing rather than cropping: a shared phone screen is portrait and the preview slot is
     * landscape, and cropping would cut the content the streamer is actually pointing at.
     */
    private fun createThumbnail(frame: VideoFrame): Bitmap {
        val frameBuffer = GlTextureFrameBuffer(GLES20.GL_RGBA)
        frameBuffer.setSize(width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer.frameBufferId)
        GlUtil.checkNoGLES2Error("glBindFramebuffer")
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val frameAspect = frame.rotatedWidth.toFloat() / frame.rotatedHeight
        if (frameAspect < width.toFloat() / height) {
            val scaledWidth = height.toFloat() / frame.rotatedHeight * frame.rotatedWidth
            frameDrawer.drawFrame(
                frame,
                rectDrawer,
                renderMatrix,
                ((width - scaledWidth) / 2f).roundToInt(),
                0,
                scaledWidth.roundToInt(),
                height,
            )
        } else {
            val scaledHeight = width.toFloat() / frame.rotatedWidth * frame.rotatedHeight
            frameDrawer.drawFrame(
                frame,
                rectDrawer,
                renderMatrix,
                0,
                ((height - scaledHeight) / 2f).roundToInt(),
                width,
                scaledHeight.roundToInt(),
            )
        }

        outputByteBuffer.rewind()
        GLES20.glReadPixels(
            0,
            0,
            frameBuffer.width,
            frameBuffer.height,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            outputByteBuffer,
        )
        GlUtil.checkNoGLES2Error("ThumbnailEmitter.createThumbnail")
        frameBuffer.release()

        outputByteBuffer.rewind()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .apply { copyPixelsFromBuffer(outputByteBuffer) }
    }

    private companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val BYTES_PER_PIXEL = 4
    }
}
