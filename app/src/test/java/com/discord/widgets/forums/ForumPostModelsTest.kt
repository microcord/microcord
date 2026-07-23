package com.discord.widgets.forums

import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForumPostModelsTest {
    @Test
    fun capsForumFooterCounts() {
        assertNull(messageCountText(null))
        assertEquals("49", messageCountText(49))
        assertEquals("50+", messageCountText(50))
        assertNull(unreadCountText(0))
        assertEquals("9", unreadCountText(9))
        assertEquals("10+", unreadCountText(10))
    }

    @Test
    fun classifiesAttachmentPreviewState() {
        val attachment = MessageAttachment(
            "https://cdn.example/image.BMP?x=1",
            1,
            2,
            "https://proxy.example/image.bmp",
            "SPOILER_image.bmp",
            16,
            16,
        )
        assertEquals(MessageAttachmentType.IMAGE, attachment.type)
        assertTrue(attachment.isSpoiler)
    }
}
