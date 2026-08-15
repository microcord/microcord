package com.discord.widgets.chat.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetChatOverlayTest {
    @Test fun formatsTypingNamesFromRecoveredTemplates() {
        assertEquals(
            "**!!Ada!!** and **!!Lin!!** are typing...",
            WidgetChatOverlay.formatTypingText("**!!{a}!!** and **!!{b}!!** are typing...", listOf("Ada", "Lin")),
        )
    }

    @Test fun disablingCounterDoesNotHideTyping() {
        assertTrue(WidgetChatOverlay.shouldShowStatusBar(typingVisible = true, counterVisible = false))
        assertFalse(WidgetChatOverlay.shouldShowStatusBar(typingVisible = false, counterVisible = false))
    }
}
