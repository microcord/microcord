package com.discord.widgets.chat.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetChatOverlayTest {
    @Test fun formatsTypingNamesFromRecoveredTemplates() {
        assertEquals(
            "Ada and Lin are typing...",
            WidgetChatOverlay.formatTypingText("**!!{a}!!** and **!!{b}!!** are typing...", listOf("Ada", "Lin")),
        )
    }
}
