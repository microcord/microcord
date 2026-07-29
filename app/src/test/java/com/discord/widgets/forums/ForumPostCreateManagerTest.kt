package com.discord.widgets.forums

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForumPostCreateManagerTest {
    @Suppress("UNCHECKED_CAST")
    @Test
    fun validatesFirstMessageLike12621() {
        var messageError: Pair<Int, Int>? = null
        assertFalse(
            ForumPostCreateManager.isValidFirstMessage(
                content = "x".repeat(2001),
                attachmentsRequest = null,
                isPremiumTier2 = false,
                isPremium = false,
                onMessageTooLong = { length, max -> messageError = length to max },
            ),
        )
        assertEquals(2001 to 2000, messageError)

        var fileError: Pair<Int, Boolean>? = null
        val oversized = ForumPostCreateManager.AttachmentsRequest(
            currentFileSizeMB = 8f,
            maxFileSizeMB = 8,
            // Validation only checks whether the collection is empty; no Android Uri is needed here.
            attachments = listOf<com.discord.utilities.attachments.UploadAttachment?>(null) as
                List<com.discord.utilities.attachments.UploadAttachment>,
        )
        assertFalse(
            ForumPostCreateManager.isValidFirstMessage(
                content = "",
                attachmentsRequest = oversized,
                isPremiumTier2 = true,
                isPremium = true,
                onFilesTooLarge = { max, premium -> fileError = max to premium },
            ),
        )
        assertEquals(8 to true, fileError)
        assertTrue(ForumPostCreateManager.isValidFirstMessage("", null, false, false))
        assertTrue(ForumPostCreateManager.isValidFirstMessage("x".repeat(4000), null, true, true))
    }
}
