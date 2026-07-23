package com.discord.widgets.user.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewGlobalSearchItemTest {
    @Test
    fun computesTheReferenceHighlightRange() {
        assertNull(highlightRange(-1, 3))
        assertEquals(2..4, highlightRange(2, 3))
    }
}
