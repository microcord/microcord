package com.discord

import android.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.utilities.view.recycler.MaxHeightRecyclerView
import com.discord.utilities.view.rounded.RoundedCoordinatorLayout
import com.discord.utilities.view.rounded.RoundedRelativeLayout
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.typing.TypingDots
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoundationalViewsSmokeTest {
    @Test
    fun foundationalViewsConstructAndAnimate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Dark)
            assertEquals(0, MaxHeightRecyclerView(context).maxHeight)
            RoundedRelativeLayout(context)
            RoundedCoordinatorLayout(context)
            LinkifiedTextView(context).text = "Manticord"
            TypingDots(context, null).run {
                start()
                stop()
            }
        }
    }
}
