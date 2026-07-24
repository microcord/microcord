package com.hammerandchisel.libdiscord

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiscordNativeLifecycleTest {
    @Test
    fun createQueryAndDispose() {
        repeat(2) {
            val discord = Discord(InstrumentationRegistry.getInstrumentation().targetContext)
            val queried = CountDownLatch(3)

            discord.getAudioSubsystem { _, _ -> queried.countDown() }
            discord.getAudioInputDevices { queried.countDown() }
            discord.getAudioOutputDevices { queried.countDown() }

            assertTrue("Native audio query timed out", queried.await(10, TimeUnit.SECONDS))
            discord.dispose()
        }
    }
}
