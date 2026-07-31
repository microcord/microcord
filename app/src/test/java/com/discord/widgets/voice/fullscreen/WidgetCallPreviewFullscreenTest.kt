package com.discord.widgets.voice.fullscreen

import android.content.Context
import com.discord.api.user.NsfwAllowance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetCallPreviewFullscreenTest {
    @Test
    fun actionBarAndInsetRulesMatch12621() {
        assertEquals(
            true to true,
            WidgetCallPreviewFullscreen.actionBarMenuVisibility(true, true, false, false, null),
        )
        assertEquals(
            false to false,
            WidgetCallPreviewFullscreen.actionBarMenuVisibility(
                true,
                true,
                true,
                false,
                NsfwAllowance.DISALLOWED,
            ),
        )
        assertEquals(
            false to false,
            WidgetCallPreviewFullscreen.actionBarMenuVisibility(true, true, true, true, null),
        )
        assertEquals(32 to 32, WidgetCallPreviewFullscreen.unreadIndicatorMargins(false, false))
        assertEquals(32 to 28, WidgetCallPreviewFullscreen.unreadIndicatorMargins(true, false))
        assertEquals(40 to 36, WidgetCallPreviewFullscreen.unreadIndicatorMargins(false, true))
        assertEquals(40 to 36, WidgetCallPreviewFullscreen.unreadIndicatorMargins(true, true))
    }

    @Test
    fun launchContractTargetsThePreviewScreen() {
        assertEquals(
            WidgetCallFullscreen.EXTRA_CHANNEL_ID,
            WidgetCallPreviewFullscreen.EXTRA_CHANNEL_ID,
        )
        assertNotNull(
            WidgetCallPreviewFullscreen::class.java.getDeclaredMethod(
                "launch",
                Context::class.java,
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ),
        )
        assertTrue(
            com.discord.app.AppFragment::class.java
                .isAssignableFrom(WidgetCallPreviewFullscreen::class.java),
        )
        assertFalse(WidgetCallFullscreen::class.java.name == WidgetCallPreviewFullscreen::class.java.name)
    }
}
