package co.discord.media_engine

import android.content.Context
import co.discord.media_engine.internal.TransformStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame

class MediaEngineContractTest {
    @Test
    fun preservesJniDescriptors() {
        assertEquals(
            listOf(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType),
            Connection::class.java.getDeclaredMethod(
                "connectUser", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            ).parameterTypes.toList(),
        )
        Connection::class.java.getDeclaredMethod(
            "startScreenshareBroadcast",
            VideoCapturer::class.java,
            Long::class.javaPrimitiveType,
        )
        NativeCapturerObserver::class.java.getDeclaredConstructor(
            Long::class.javaPrimitiveType,
            Context::class.java,
            VideoCapturer::class.java,
        )
        NativeCapturerObserver::class.java.getDeclaredMethod(
            "nativeOnFrame",
            Long::class.javaPrimitiveType,
            VideoFrame.Buffer::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        assertEquals(listOf("Audio", "Video"), MediaType.values().map { it.name })
    }

    @Test
    fun transformsEmptyNativeStats() {
        val stats = TransformStats.transform("{}")
        assertEquals(0L, stats.transport?.bytesReceived)
        assertEquals(0L, stats.transport?.bytesSent)
        assertTrue(stats.inboundRtpAudio.isEmpty())
        assertTrue(stats.inboundRtpVideo.isEmpty())
    }
}
