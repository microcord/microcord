package com.discord.native.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeEngineLifecycleTest {
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
