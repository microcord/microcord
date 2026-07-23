package com.discord.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FailedUploadListTest {
    @Test
    fun formatsUploadSizesLikeDiscord() {
        assertNull(FailedUploadList.sizeSubtitle(-1))
        assertEquals("512 B", FailedUploadList.sizeSubtitle(512))
        assertEquals("1.5 KB", FailedUploadList.sizeSubtitle(1536))
        assertEquals("2.0 MB", FailedUploadList.sizeSubtitle(2L * 1024 * 1024))
    }
}
