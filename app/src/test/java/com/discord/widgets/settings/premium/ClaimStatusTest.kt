package com.discord.widgets.settings.premium

import com.discord.api.utcdatetime.UtcDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClaimStatusTest {
    @Test
    fun `claim contracts preserve reference field order and redact codes`() {
        val claimed = ClaimStatus.Claimed(7L, "secret-code", "body", "https://example.com", UtcDateTime(9L))
        val unclaimed = ClaimStatus.Unclaimed(7L, UtcDateTime(8L))

        assertEquals(7L, claimed.component1())
        assertEquals("secret-code", claimed.component2())
        assertEquals("body", claimed.component3())
        assertEquals("https://example.com", claimed.component4())
        assertEquals(UtcDateTime(9L), claimed.component5())
        assertEquals(7L, unclaimed.component1())
        assertEquals(UtcDateTime(8L), unclaimed.component2())
        assertFalse(claimed.toString().contains("secret-code"))
    }
}
