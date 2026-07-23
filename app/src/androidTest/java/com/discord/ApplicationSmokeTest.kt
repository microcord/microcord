package com.discord

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.app.App
import com.discord.app.AppActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplicationSmokeTest {
    @Test
    fun applicationAndXmlActivityLaunch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        assertEquals("dev.mantikafasi.manticord", context.packageName)
        assertTrue(context.applicationContext is App)

        val activity = instrumentation.startActivitySync(
            Intent(context, AppActivity.Main::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        assertNotNull(activity.findViewById(R.id.app_root))
        instrumentation.runOnMainSync(activity::finish)
    }
}
