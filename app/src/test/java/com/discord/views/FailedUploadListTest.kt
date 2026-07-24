package com.discord.views

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FailedUploadListTest {
    @Test
    fun formatsUploadSizesLikeDiscord() {
        assertNull(FailedUploadList.sizeSubtitle(-1))
        assertEquals("512 B", FailedUploadList.sizeSubtitle(512))
        assertEquals(String.format(Locale.getDefault(), "%.1f KB", 1.5), FailedUploadList.sizeSubtitle(1536))
        assertEquals(String.format(Locale.getDefault(), "%.1f MB", 2.0), FailedUploadList.sizeSubtitle(2L * 1024 * 1024))
    }
}
