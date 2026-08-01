package com.discord.widgets.share

import com.discord.api.premium.PremiumTier
import org.junit.Assert.assertEquals
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
    }

    @Test
    fun uploadLimitUsesTheLargerAccountOrGuildAllowance() {
        assertEquals(8, WidgetIncomingShare.uploadLimitMB(PremiumTier.NONE, 0, false))
        assertEquals(50, WidgetIncomingShare.uploadLimitMB(PremiumTier.NONE, 2, false))
        assertEquals(100, WidgetIncomingShare.uploadLimitMB(PremiumTier.TIER_2, 0, false))
        assertEquals(200, WidgetIncomingShare.uploadLimitMB(PremiumTier.NONE, 3, true))
    }
}
