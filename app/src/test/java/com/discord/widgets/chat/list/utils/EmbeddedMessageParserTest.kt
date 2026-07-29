package com.discord.widgets.chat.list.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedMessageParserTest {

    @Test
    fun `embedded message content is collapsed to one line`() {
        assertEquals(
            "first second third",
            collapseEmbeddedMessageLines("first\r\nsecond\r\nthird", "\r\n"),
        )
    }

    @Test
    fun `other characters are left untouched`() {
        assertEquals("first\nsecond", collapseEmbeddedMessageLines("first\nsecond", "\r\n"))
    }
}
