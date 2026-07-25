package com.discord.utilities.io

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDownloadManagerTest {
    @Test fun validatesOnlyAbsoluteHttpsUrls() {
        assertTrue(isValidDownloadUrl("https://cdn.example.com/files/report.pdf"))
        assertTrue(isValidDownloadUrl("HTTPS://example.com/file"))
        assertFalse(isValidDownloadUrl("http://example.com/file"))
        assertFalse(isValidDownloadUrl("https:///file"))
        assertFalse(isValidDownloadUrl("not a url"))
    }

    @Test fun sanitizesTraversalUnsafeAndOversizedNames() {
        assertEquals("report_.pdf", sanitizeDownloadFileName("../folder/report?.pdf"))
        assertEquals("file", sanitizeDownloadFileName(".."))
        val longName = sanitizeDownloadFileName("a".repeat(120) + ".png")
        assertEquals(100, longName.length)
        assertTrue(longName.endsWith(".png"))
    }

    @Test fun mapsOnlySuccessfulRowsWithLocalUrisToSuccess() {
        assertEquals(
            UserDownloadResult.Success(7, "report.pdf", "content://downloads/7"),
            mapDownloadResult(7, DownloadManager.STATUS_SUCCESSFUL, "report.pdf", "content://downloads/7"),
        )
        assertEquals(
            UserDownloadResult.Failure(7, UserDownloadResult.Failure.Reason.SYSTEM_FAILED),
            mapDownloadResult(7, DownloadManager.STATUS_FAILED, "report.pdf", "content://downloads/7"),
        )
        assertEquals(
            UserDownloadResult.Failure(7, UserDownloadResult.Failure.Reason.MISSING_LOCAL_URI),
            mapDownloadResult(7, DownloadManager.STATUS_SUCCESSFUL, "report.pdf", null),
        )
        assertEquals(
            UserDownloadResult.Failure(7, UserDownloadResult.Failure.Reason.INCOMPLETE),
            mapDownloadResult(7, DownloadManager.STATUS_RUNNING, "report.pdf", null),
        )
    }
}
