package com.discord.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoticeHostTest {
    @Test
    fun resumedActivityShowsQueuedNotice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, AppActivity.Main::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val noticeName = "instrumentation notice"
        val shown = CountDownLatch(1)

        val notices = StoreStream.getNotices()
        notices.requestToShow(
            StoreNotices.Notice(
                name = noticeName,
                priority = StoreNotices.PRIORITY_HIGH,
                validScreens = null,
                delayPeriodMs = 0L,
                sinceLastPeriodMs = 0L,
            ) {
                shown.countDown()
                true
            },
        )
        notices.processNextNotice()

        assertTrue("queued notice was not delivered to the resumed activity", shown.await(5, TimeUnit.SECONDS))
        notices.markSeen(noticeName)
        instrumentation.runOnMainSync(activity::finish)
    }
}
