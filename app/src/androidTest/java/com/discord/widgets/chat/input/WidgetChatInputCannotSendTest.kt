package com.discord.widgets.chat.input

import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.RelativeLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.databinding.FlexInputWidgetBinding
import com.discord.utilities.color.ColorCompat
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetChatInputCannotSendTest {
    @Test
    fun sendPermissionReplacesComposerWithExactPassiveBlockerAndRestoresIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Dark)
            val binding = FlexInputWidgetBinding.inflate(LayoutInflater.from(context))
            val blocker = binding.cannotSendText
            val density = context.resources.displayMetrics.density

            binding.renderSendPermission(false)

            assertEquals(View.VISIBLE, blocker.visibility)
            assertEquals(context.getString(R.string.no_send_messages_permission_placeholder), blocker.text.toString())
            assertEquals(Gravity.CENTER, blocker.gravity)
            assertEquals((12 * density).roundToInt(), blocker.paddingLeft)
            assertEquals((24 * density).roundToInt(), blocker.paddingTop)
            assertEquals((12 * density).roundToInt(), blocker.paddingRight)
            assertEquals((24 * density).roundToInt(), blocker.paddingBottom)
            assertNotNull(blocker.background)
            assertEquals(
                ColorCompat.getThemedColor(context, R.attr.colorBackgroundSecondary),
                (blocker.background as ColorDrawable).color,
            )
            assertTrue(blocker.isFocusable)
            assertTrue(blocker.isClickable)
            assertFalse(blocker.hasOnClickListeners())
            assertEquals(View.VISIBLE, binding.mainInputContainer.visibility)
            val parent = blocker.parent as RelativeLayout
            parent.measure(
                View.MeasureSpec.makeMeasureSpec(1_080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
            assertEquals(0, blocker.left)
            assertEquals(parent.width, blocker.right)
            assertEquals(parent.childCount - 1, parent.indexOfChild(blocker))

            binding.renderSendPermission(true)

            assertEquals(View.GONE, blocker.visibility)
        }
    }
}
