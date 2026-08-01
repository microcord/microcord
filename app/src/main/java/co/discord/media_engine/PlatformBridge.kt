package co.discord.media_engine

import android.content.Context
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame

object CameraEnumeratorProvider {
    private var enumerator: CameraEnumerator? = null

    @JvmStatic
    @Synchronized
    fun maybeInit(context: Context) {
        if (enumerator == null) init(context, false)
    }

    @JvmStatic
    @Synchronized
    fun get(): CameraEnumerator = enumerator ?: Camera1Enumerator()

    @JvmStatic
    fun createCapturer(cameraName: String): CameraVideoCapturer =
        requireNotNull(get().createCapturer(cameraName, null))

    @Synchronized
    fun init(context: Context, forceCamera1: Boolean) {
        enumerator = if (!forceCamera1 && Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context.applicationContext)
        } else {
            Camera1Enumerator()
        }
    }
}

class SharedEglBaseContext {
    companion object {
        private var eglBase: EglBase? = null

        @JvmStatic
        @Synchronized
        fun getEglContext(): EglBase.Context {
            val base = eglBase ?: EglBase.create(null, EglBase.CONFIG_PLAIN).also { eglBase = it }
            return base.eglBaseContext
        }
    }
}

class NativeCapturerObserver : CapturerObserver {
    private var nativeHandle_: Long
    private var startCaptureOnReconfigure_ = true
    private var surfaceTextureHelper_: SurfaceTextureHelper?
    private var videoCapturer_: VideoCapturer?

    constructor(nativeHandle: Long, context: Context, cameraName: String) :
        this(nativeHandle, context, CameraEnumeratorProvider.createCapturer(cameraName))

    constructor(nativeHandle: Long, context: Context, videoCapturer: VideoCapturer) {
        nativeHandle_ = nativeHandle
        videoCapturer_ = videoCapturer
        surfaceTextureHelper_ = SurfaceTextureHelper.create("DiscordTextureCapture", SharedEglBaseContext.getEglContext())
        videoCapturer.initialize(surfaceTextureHelper_, context, this)
    }

    private external fun nativeOnFrame(
        nativeHandle: Long,
        buffer: VideoFrame.Buffer,
        width: Int,
        height: Int,
        rotation: Int,
        timestampNs: Long,
    )

    @Synchronized
    fun dispose() {
        videoCapturer_?.let {
            try {
                it.stopCapture()
            } catch (e: InterruptedException) {
                throw RuntimeException(e)
            }
            it.dispose()
            videoCapturer_ = null
        }
        surfaceTextureHelper_?.dispose()
        surfaceTextureHelper_ = null
    }

    @Synchronized
    fun reconfigure(width: Int, height: Int, framerate: Int) {
        val capturer = videoCapturer_ ?: return
        if (startCaptureOnReconfigure_) {
            startCaptureOnReconfigure_ = false
            capturer.startCapture(width, height, framerate)
        } else {
            capturer.changeCaptureFormat(width, height, framerate)
        }
    }

    fun repeatLastFrame() {
        surfaceTextureHelper_?.forceFrame()
    }

    override fun onCapturerStarted(success: Boolean) = Unit
    override fun onCapturerStopped() = Unit

    override fun onFrameCaptured(frame: VideoFrame) {
        nativeOnFrame(
            nativeHandle_,
            frame.buffer,
            frame.rotatedWidth,
            frame.rotatedHeight,
            frame.rotation,
            frame.timestampNs,
        )
    }

    companion object {
        init {
            System.loadLibrary("discord")
        }
    }
}
