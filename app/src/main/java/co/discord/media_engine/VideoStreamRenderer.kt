package co.discord.media_engine

import android.content.Context
import android.util.AttributeSet
import com.hammerandchisel.libdiscord.Discord
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
    private var streamIdentifier = ""

    fun attachToStream(
        discord: Discord?,
        streamId: String?,
        events: RendererCommon.RendererEvents? = null,
    ) {
        if (discord == null) return
        if (streamIdentifier.isNotEmpty()) {
            clearImage()
            release()
            removeSink(discord, streamIdentifier)
        }
        streamIdentifier = streamId.orEmpty()
        if (streamIdentifier.isNotEmpty()) {
            init(SharedEglBaseContext.getEglContext(), events)
            addSink(discord, streamIdentifier)
        }
    }

    fun attachToStream(
        sink: VideoOutputSink?,
        streamId: String?,
        events: RendererCommon.RendererEvents? = null,
    ) = attach(sink?.let { it::setVideoOutputSink }, streamId, events)

    private fun attach(
        setSink: ((String, VideoFrameCallback?) -> Unit)?,
        streamId: String?,
        events: RendererCommon.RendererEvents?,
    ) {
        if (setSink == null) return
        if (streamIdentifier.isNotEmpty()) {
            clearImage()
            release()
            removeSink(setSink, streamIdentifier)
        }
        streamIdentifier = streamId.orEmpty()
        if (streamIdentifier.isNotEmpty()) {
            init(SharedEglBaseContext.getEglContext(), events)
            addSink(setSink, streamIdentifier)
        }
    }

    private fun addSink(discord: Discord, streamId: String) = synchronized(streams) {
        addSink({ id, callback ->
            discord.setVideoOutputSink(id, callback?.let { Discord.VideoFrameCallback(it::onFrame) })
        }, streamId)
    }

    private fun addSink(setSink: (String, VideoFrameCallback?) -> Unit, streamId: String) = synchronized(streams) {
        val sinks = streams[streamId]
        if (sinks != null) {
            sinks += this
        } else {
            val newSinks = linkedSetOf(this)
            streams[streamId] = newSinks
            setSink(streamId) { frame ->
                synchronized(newSinks) { newSinks.toList() }.forEach { it.onFrame(frame) }
                true
            }
        }
    }

    private fun removeSink(discord: Discord, streamId: String) = synchronized(streams) {
        removeSink({ id, callback ->
            discord.setVideoOutputSink(id, callback?.let { Discord.VideoFrameCallback(it::onFrame) })
        }, streamId)
    }

    private fun removeSink(setSink: (String, VideoFrameCallback?) -> Unit, streamId: String) = synchronized(streams) {
        val sinks = streams[streamId] ?: return
        synchronized(sinks) { sinks -= this }
        if (sinks.isEmpty()) {
            setSink(streamId, null)
            streams -= streamId
        }
    }

    private companion object {
        val streams = linkedMapOf<String, MutableSet<VideoStreamRenderer>>()
    }
}
