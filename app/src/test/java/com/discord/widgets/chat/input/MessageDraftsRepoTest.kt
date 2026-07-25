package com.discord.widgets.chat.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageDraftsRepoTest {
    @Test fun draftsAreIsolatedAndEmptyTextDeletesOnlyItsChannel() {
        val drafts = MessageDraftsRepo()
        drafts.setTextChannelInput(1, "one")
        drafts.setTextChannelInput(2, "two")

        assertEquals("one", drafts.getTextChannelInput(1))
        assertEquals("two", drafts.getTextChannelInput(2))
        drafts.setTextChannelInput(1, "")
        assertNull(drafts.getTextChannelInput(1))
        assertEquals("two", drafts.getTextChannelInput(2))
    }
}
