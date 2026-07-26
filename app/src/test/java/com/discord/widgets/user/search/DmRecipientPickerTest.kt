package com.discord.widgets.user.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DmRecipientPickerTest {
    @Test
    fun matchesRecipientsByNameAndDiscriminator() {
        assertTrue(recipientMatches("Alice", 42, "alice#42"))
        assertTrue(recipientMatches("Alice", 42, "ALI"))
        assertTrue(recipientMatches("Alice", 42, "   "))
        assertFalse(recipientMatches("Alice", 42, "bob"))
    }
}
