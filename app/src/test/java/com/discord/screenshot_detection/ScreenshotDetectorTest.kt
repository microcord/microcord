package com.discord.screenshot_detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotDetectorTest {
    @Test
    fun `accepts only fresh unique images in a screenshots directory`() {
        val now = 100_000L
        assertTrue(ScreenshotDetector.isRecentScreenshot("Pictures/Screenshots/a.png", 100L, now, null))
        assertFalse(ScreenshotDetector.isRecentScreenshot("Pictures/Camera/a.png", 100L, now, null))
        assertFalse(ScreenshotDetector.isRecentScreenshot("Pictures/Screenshots/a.png", 89L, now, null))
        assertFalse(
            ScreenshotDetector.isRecentScreenshot(
                "Pictures/Screenshots/a.png",
                100L,
                now,
                "Pictures/Screenshots/a.png",
            ),
        )
    }
}
