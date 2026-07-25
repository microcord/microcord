package com.discord.widgets.user.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DmRecipientPickerTest {
    @Test
    fun normalizesAndMatchesDmQueries() {
        assertEquals("alice", normalizedDmQuery("  @alice "))
        assertEquals(3, dmMatchedResult("Hi Alice", "alice").firstMatchIndex)
        assertTrue(recipientMatches("Alice", 42, "alice#42"))
        assertFalse(recipientMatches("Alice", 42, "bob"))
    }
}
