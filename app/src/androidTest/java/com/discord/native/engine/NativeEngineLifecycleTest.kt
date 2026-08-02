package com.discord.native.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.rtcconnection.mediaengine.EchoCancellationConfig
import com.discord.rtcconnection.mediaengine.MediaEngine
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.rtcconnection.mediaengine.MediaEngineLegacy
import com.discord.rtcconnection.mediaengine.MediaEngineThreadExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeEngineLifecycleTest {
    @Test
    fun voiceStatusListenerDoesNotStartNativeCapture() {
        val executor = MediaEngineThreadExecutor.create()
        try {
            val engine = MediaEngineLegacy(
                context = InstrumentationRegistry.getInstrumentation().targetContext,
                listener = object : MediaEngine.Listener {
                    override fun onConnected() = Unit
                    override fun onConnecting() = Unit
                    override fun onNativeEngineInitialized() = Unit
                    override fun onNewConnection(connection: MediaEngineConnection) = Unit
                },
                mediaEngineThreadExecutor = executor,
                openSLESConfig = MediaEngine.OpenSLESConfig.DEFAULT,
                echoCancellation = EchoCancellationConfig(),
                echoCancellationCallback = MediaEngine.EchoCancellationCallback { _ -> },
            )

            engine.setLocalVoiceStatusListener { _ -> }

            assertNull("Registering VAD must not initialize native audio", engine.getNativeEngine())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun createQueryAndDisposeTwice() {
        repeat(2) {
            val engine = NativeEngine(InstrumentationRegistry.getInstrumentation().targetContext, NativeEngine.LOGLEVEL_DEFAULT)
            val queried = CountDownLatch(1)
            engine.getAudioSubsystem(object : NativeEngine.GetAudioSubsystemCallback {
                override fun onAudioSubsystem(subsystem: String, audioLayer: String) = queried.countDown()
            })
            assertTrue("Native audio-subsystem query timed out", queried.await(10, TimeUnit.SECONDS))
            engine.dispose()
        }
    }
}
