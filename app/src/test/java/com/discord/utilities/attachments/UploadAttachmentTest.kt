package com.discord.utilities.attachments

import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
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
}
