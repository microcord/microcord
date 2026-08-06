package com.discord.utilities.messagesend

import com.discord.restapi.RestAPIParams
import com.discord.utilities.attachments.CloudUploader
import org.junit.Assert.assertEquals
import org.junit.Test

class ClaimUploadsTest {

    @Test
    fun `entries renamed for uniqueness still claim their own upload`() {
        // `SendUtils` renames the second of two identically named files, so matching on the name
        // would leave it unclaimed and pin both entries to the first upload.
        val message = message("photo.png", "photo-1.png")
        val uploads = listOf(staged("aaa/photo.png"), staged("bbb/photo.png"))

        val claimed = claimUploads(message, uploads)

        assertEquals(
            listOf("aaa/photo.png", "bbb/photo.png"),
            claimed.attachments?.map { it.uploadedFilename },
        )
    }

    @Test
    fun `an entry with no matching upload is left unclaimed rather than mispointed`() {
        val claimed = claimUploads(message("a.png", "b.png"), listOf(staged("aaa/a.png")))
        assertEquals(listOf("aaa/a.png", null), claimed.attachments?.map { it.uploadedFilename })
    }

    @Test
    fun `a message with no attachments is untouched`() {
        val message = RestAPIParams.Message(content = "hi")
        assertEquals(null, claimUploads(message, emptyList()).attachments)
    }

    private fun message(vararg filenames: String) = RestAPIParams.Message(
        content = "hi",
        attachments = filenames.mapIndexed { index, name ->
            RestAPIParams.Message.Attachment(id = index, filename = name, isSpoiler = false)
        },
    )

    private fun staged(uploadedFilename: String) = CloudUploader.StagedUpload(uploadedFilename)
}
