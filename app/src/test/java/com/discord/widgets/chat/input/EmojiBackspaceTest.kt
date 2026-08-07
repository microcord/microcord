package com.discord.widgets.chat.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmojiBackspaceTest {
    @Test
    fun deletesSelectionOrPreviousCodePoint() {
        assertEquals(IntRange(1, 3), deletionRangeBeforeCursor("a😀", 3, 3))
        assertEquals(IntRange(1, 3), deletionRangeBeforeCursor("abc", 3, 1))
        assertEquals(IntRange(2, 3), deletionRangeBeforeCursor("abc", 3, 3))
        assertNull(deletionRangeBeforeCursor("abc", 0, 0))
    }
}
