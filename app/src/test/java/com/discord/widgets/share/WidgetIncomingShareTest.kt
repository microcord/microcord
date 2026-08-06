package com.discord.widgets.share

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetIncomingShareTest {
    @Test
    fun sendRequiresARecipientAndContent() {
        assertFalse(WidgetIncomingShare.canSend(false, "hello", 0))
        assertFalse(WidgetIncomingShare.canSend(true, "  ", 0))
        assertTrue(WidgetIncomingShare.canSend(true, "hello", 0))
        assertTrue(WidgetIncomingShare.canSend(true, "", 1))
        assertTrue(WidgetIncomingShare.canSend(true, "", 0, forwarding = true))
    }

}
