package co.discord.media_engine

import android.content.Context
import android.util.AttributeSet
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame

fun interface VideoFrameCallback {
    fun onFrame(frame: VideoFrame): Boolean
}

interface VideoOutputSink {
    fun setVideoOutputSink(streamId: String, callback: VideoFrameCallback?)
}

open class VideoStreamRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceViewRenderer(context, attrs) {
    private var engine: VideoOutputSink? = null
    private var streamId = ""

    fun attachToStream(
        engine: VideoOutputSink?,
        streamId: String?,
        events: RendererCommon.RendererEvents? = null,
    ) {
        if (this.streamId.isNotEmpty()) {
            clearImage()
            release()
            removeSink(this.engine, this.streamId)
        }
        this.engine = engine
        this.streamId = streamId.orEmpty()
        if (engine != null && this.streamId.isNotEmpty()) {
            init(sharedEglBase.eglBaseContext, events)
            addSink(engine, this.streamId)
        }
    }

    private fun addSink(engine: VideoOutputSink, streamId: String) = synchronized(streams) {
        val sinks = streams[streamId]
        if (sinks != null) {
            sinks += this
        } else {
            val newSinks = linkedSetOf(this)
            streams[streamId] = newSinks
            engine.setVideoOutputSink(streamId) { frame ->
                synchronized(newSinks) { newSinks.toList() }.forEach { it.onFrame(frame) }
                true
            }
        }
    }

    private fun removeSink(engine: VideoOutputSink?, streamId: String) = synchronized(streams) {
        val sinks = streams[streamId] ?: return
        synchronized(sinks) { sinks -= this }
        if (sinks.isEmpty()) {
            engine?.setVideoOutputSink(streamId, null)
            streams -= streamId
        }
    }

    private companion object {
        val streams = linkedMapOf<String, MutableSet<VideoStreamRenderer>>()
        val sharedEglBase: EglBase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { EglBase.create() }
    }
}
