package com.discord.widgets.forums

import com.discord.restapi.RestAPIParams
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.discord.utilities.attachments.AttachmentLimits
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

        var fileError: Pair<AttachmentLimits.Violation, Boolean>? = null
        val oversized = ForumPostCreateManager.AttachmentsRequest(
            sizes = listOf(AttachmentLimits.MAX_ATTACHMENT_SIZE + 1),
            maxFileSize = AttachmentLimits.MAX_ATTACHMENT_SIZE,
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
                onFilesTooLarge = { violation, premium -> fileError = violation to premium },
            ),
        )
        assertEquals(
            AttachmentLimits.Violation.FileTooLarge(
                AttachmentLimits.MAX_ATTACHMENT_SIZE + 1,
                AttachmentLimits.MAX_ATTACHMENT_SIZE,
            ) to true,
            fileError,
        )
        assertTrue(ForumPostCreateManager.isValidFirstMessage("", null, false, false))
        assertTrue(ForumPostCreateManager.isValidFirstMessage("x".repeat(4000), null, true, true))
    }

    @Test
    fun currentForumPayloadNestsTheFirstMessage() {
        val body = ForumPostCreateManager.forumPostBody(
            "Post title",
            1_440,
            RestAPIParams.Message(content = "Post body", nonce = "nonce", stickerIds = emptyList()),
        )
        val json = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create().toJsonTree(body).asJsonObject

        assertEquals("Post title", json["name"].asString)
        assertEquals(1_440, json["auto_archive_duration"].asInt)
        assertEquals("Post body", json["message"].asJsonObject["content"].asString)
        assertFalse(json.has("content"))
    }
}
