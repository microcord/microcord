package com.discord.widgets.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MessageContentTest {
    @Test
    fun `preserves data contract without rendering message text`() {
        val content = MessageContent("private message", emptyList())

        assertEquals("private message", content.component1())
        assertEquals(emptyList<com.discord.models.user.User>(), content.component2())
        assertEquals(content, content.copy())
        assertFalse(content.toString().contains("private message"))
    }
}
