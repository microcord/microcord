package com.discord.widgets.chat.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAttachmentViewTest {
    @Test
    fun textPreviewAcceptsTextFilesAndRejectsBinaryContent() {
        assertTrue(isTextPreviewFilename("notes.TXT"))
        assertTrue(isTextPreviewFilename("Dockerfile.dev"))
        assertFalse(isTextPreviewFilename("archive.zip"))
        assertEquals("kotlin", syntaxLanguageForFilename("Example.kt"))
        assertEquals("diff", syntaxLanguageForFilename("change.patch"))
        assertNull(syntaxLanguageForFilename("notes.txt"))
        assertEquals("hello\nworld", decodeTextPreview("hello\nworld".toByteArray()))
        assertNull(decodeTextPreview(byteArrayOf(1, 0, 2)))
    }
}
