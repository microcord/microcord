package co.discord.media_engine

import android.content.Context
import android.view.Surface
import co.discord.media_engine.internal.TransformStats
import com.discord.DiscordDecoderStreamManager
import com.discord.DiscordDecoderStreamManagerDelegate
import com.discord.crash_reporting.WebrtcCrashReporting
import com.discord.native.engine.NativeConnection
import com.discord.native.engine.NativeEngine
import com.discord.play_delivery.PlayAssetDeliveryNativeWrapper
import com.google.gson.Gson
import java.lang.reflect.Modifier
import java.util.function.Consumer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.AndroidVideoDecoder
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
    fun modernConnectionAndLookupStubsHaveExactSignatures() {
        assertTrue(Connection::class.java.declaredMethods.none { Modifier.isNative(it.modifiers) })
        Connection::class.java.getDeclaredConstructor(
            NativeConnection::class.java,
            List::class.java,
            NativeEngine::class.java,
            String::class.java,
        )
        Connection::class.java.getDeclaredMethod(
            "prepareMLSCommitTransitionB64",
            Int::class.javaPrimitiveType,
            String::class.java,
            NativeConnection.MLSCommitTransitionCallback::class.java,
        )
        Connection::class.java.getDeclaredMethod(
            "processMLSWelcomeB64",
            Int::class.javaPrimitiveType,
            String::class.java,
            NativeConnection.MLSWelcomeCallback::class.java,
        )
        DiscordDecoderStreamManager::class.java.getDeclaredMethod(
            "registerStreamProducer",
            String::class.java,
            AndroidVideoDecoder::class.java,
        )
        DiscordDecoderStreamManagerDelegate::class.java.getDeclaredMethod(
            "addOnConsumerReadyListener",
            String::class.java,
            Consumer::class.java,
        )
        assertEquals(Surface::class.java, DiscordDecoderStreamManager::class.java.getDeclaredMethod("getStreamConsumer", String::class.java).returnType)
        assertEquals(String::class.java, WebrtcCrashReporting::class.java.getDeclaredMethod("reportWebrtcException", Throwable::class.java).returnType)
        assertEquals(String::class.java, PlayAssetDeliveryNativeWrapper::class.java.getDeclaredMethod("getKrispAssetPackLocation").returnType)
        assertFalse(Modifier.isNative(Connection::class.java.getDeclaredMethod("dispose").modifiers))
    }

    @Test
    fun userConnectionJsonUsesNamedModernFields() {
        val model = Connection.UserConnectionInfo("42", 10, videoSsrcs = listOf(20), rtxSsrcs = listOf(21), volume = 0.5f, mute = true)
        val json = Gson().toJson(model)
        assertTrue(json.contains("\"audioSsrc\":10"))
        assertTrue(json.contains("\"videoSsrcs\":[20]"))
        assertEquals(model, Gson().fromJson(json, Connection.UserConnectionInfo::class.java))
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
