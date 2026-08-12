package com.discord.utilities.attachments

import com.discord.api.message.attachment.MessageAttachment
import com.discord.restapi.RestAPIParams
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class UploadAttachmentTest {
    @Test fun sanitizesSpoilersAndUnsafeNames() {
        assertEquals("SPOILER_resume_.png", sanitizeUploadFileName("../résumé?.png", spoiler = true))
        assertEquals("report.pdf", sanitizeUploadFileName("SPOILER_report.pdf"))
        assertEquals(
            listOf("file.txt", "file-1.TXT", "file-2.txt"),
            uniqueUploadFileNamesFromSanitized(listOf("file.txt", "file.TXT", "file.txt")),
        )
    }

    @Test fun rejectsUnreadableMetadataAndDefaultsUnknownValues() {
        assertThrows(FileNotFoundException::class.java) { validateAttachmentMetadata(false, null, null) }
        assertEquals("application/octet-stream" to -1L, validateAttachmentMetadata(true, null, null))
        assertEquals("image/png" to 12L, validateAttachmentMetadata(true, "image/png", 12L))
    }

    @Test fun modernSpoilerMetadataSerializesAndIncomingFlagsRender() {
        val body = RestAPIParams.Message(
            attachments = listOf(RestAPIParams.Message.Attachment(0, "image.png", true)),
        )
        val json = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create().toJsonTree(body).asJsonObject
        assertTrue(json["attachments"].asJsonArray[0].asJsonObject["is_spoiler"].asBoolean)
        val remixBody = RestAPIParams.Message(
            attachments = listOf(
                RestAPIParams.Message.Attachment(
                    0,
                    "edited.png",
                    false,
                    isRemix = true,
                ),
            ),
        )
        val remixJson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create().toJsonTree(remixBody).asJsonObject
        val remixAttachment = remixJson["attachments"].asJsonArray[0].asJsonObject
        assertTrue(remixAttachment["is_remix"].asBoolean)
        assertFalse(remixAttachment.has("flags"))

        fun attachment(filename: String, flags: Int?) = MessageAttachment(
            url = "https://cdn.test/$filename",
            size = 1,
            id = 1,
            proxyUrl = "https://proxy.test/$filename",
            filename = filename,
            width = 1,
            height = 1,
            flags = flags,
        )
        assertTrue(attachment("image.png", MessageAttachment.FLAG_IS_SPOILER).isSpoiler)
        assertTrue(attachment("SPOILER_image.png", null).isSpoiler)
        assertFalse(attachment("image.png", null).isSpoiler)
        assertTrue(attachment("edited.png", MessageAttachment.FLAG_IS_REMIX).isRemix)
    }
}
