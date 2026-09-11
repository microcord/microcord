package com.discord.utilities.display

import android.app.Dialog
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RefreshRateRequestTest {
    @Test
    fun requestsFastestModeWithoutChangingResolution() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val appContext = instrumentation.targetContext
            val display = (appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY)
            val context = ContextThemeWrapper(appContext.createDisplayContext(display), R.style.AppTheme_Dark)
            val window = requireNotNull(Dialog(context).window)
            window.setMaximumRefreshRate(true)
            if (Build.VERSION.SDK_INT >= 23) {
                val current = display.mode
                val requested = display.supportedModes.single { it.modeId == window.attributes.preferredDisplayModeId }
                assertEquals(current.physicalWidth, requested.physicalWidth)
                assertEquals(current.physicalHeight, requested.physicalHeight)
                assertEquals(requested.refreshRate, window.attributes.preferredRefreshRate, 0.01f)
                assertFalse(display.supportedModes.any {
                    it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight &&
                        it.refreshRate > requested.refreshRate
                })
            }
            window.setMaximumRefreshRate(false)
            assertEquals(0f, window.attributes.preferredRefreshRate, 0f)
            if (Build.VERSION.SDK_INT >= 23) assertEquals(0, window.attributes.preferredDisplayModeId)
        }
    }
}
