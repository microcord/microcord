package com.discord.widgets.servers

import android.content.Intent
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import androidx.appcompat.content.res.AppCompatResources
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.app.AppActivity
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetServerSettingsEmojisButtonTest {
    @Test
    fun uploadClearsPressedStateBeforeActivityLifecycleAndKeepsStyleTint() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, AppActivity.Main::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AppActivity.Main
        val monitor = instrumentation.addMonitor(AppActivity.Main::class.java.name, null, false)
        lateinit var upload: MaterialButton
        var pressedWhenOpening = true

        instrumentation.runOnMainSync {
            val themedContext = ContextThemeWrapper(activity, R.style.AppTheme_Dark)
            val header = LayoutInflater.from(themedContext)
                .inflate(R.layout.widget_server_settings_emojis_header, null)
            activity.setContentView(header)
            upload = header.findViewById(R.id.widget_server_settings_emojis_upload)
            upload.setOnClickListener {
                upload.post {
                    pressedWhenOpening = upload.isPressed
                    activity.startActivity(Intent(activity, AppActivity.Main::class.java))
                }
            }
        }
        instrumentation.waitForIdleSync()

        instrumentation.runOnMainSync {
            val now = SystemClock.uptimeMillis()
            val x = upload.width / 2f
            val y = upload.height / 2f
            MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).run {
                upload.dispatchTouchEvent(this)
                recycle()
            }
            MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_UP, x, y, 0).run {
                upload.dispatchTouchEvent(this)
                recycle()
            }
        }

        val opened = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5_000))
        instrumentation.removeMonitor(monitor)
        instrumentation.runOnMainSync(opened::finish)
        instrumentation.waitForIdleSync()

        instrumentation.runOnMainSync {
            val selector = AppCompatResources.getColorStateList(
                upload.context,
                R.color.uikit_btn_bg_color_selector_brand,
            )!!
            val tint = upload.backgroundTintList!!
            assertFalse(pressedWhenOpening)
            assertFalse(upload.isPressed)
            assertFalse(upload.isActivated)
            assertFalse(upload.isSelected)
            assertTrue(upload.isEnabled)
            assertEquals(
                selector.getColorForState(upload.drawableState, selector.defaultColor),
                tint.getColorForState(upload.drawableState, tint.defaultColor),
            )
            activity.finish()
        }
    }
}
