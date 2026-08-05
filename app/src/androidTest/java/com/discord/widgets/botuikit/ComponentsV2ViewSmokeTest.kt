package com.discord.widgets.botuikit

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.api.botuikit.ComponentType
import com.discord.databinding.BotUikitModalCheckboxBinding
import com.discord.databinding.BotUikitModalLabelBinding
import com.discord.databinding.BotUikitModalSelectBinding
import com.discord.databinding.BotUikitModalTextDisplayBinding
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentsV2ViewSmokeTest {
    @Test
    fun currentModalAndMessageComponentsInflateAndMeasure() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().targetContext,
                R.style.AppTheme_Dark,
            )
            val parent = FrameLayout(context)
            val inflater = LayoutInflater.from(context)

            BotUikitModalCheckboxBinding.inflate(inflater, parent, false)
            BotUikitModalLabelBinding.inflate(inflater, parent, false)
            BotUikitModalSelectBinding.inflate(inflater, parent, false)
            BotUikitModalTextDisplayBinding.inflate(inflater, parent, false)

            listOf(
                ComponentType.SECTION,
                ComponentType.TEXT_DISPLAY,
                ComponentType.THUMBNAIL,
                ComponentType.MEDIA_GALLERY,
                ComponentType.FILE,
                ComponentType.SEPARATOR,
                ComponentType.CONTAINER,
            ).forEach { type ->
                val view = ComponentInflater.from(context).inflateComponent(type, parent)
                assertNotNull(type.name, view)
                (view as View).measure(
                    View.MeasureSpec.makeMeasureSpec(dp(context, 360), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
        }
    }

    private fun dp(context: android.content.Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
