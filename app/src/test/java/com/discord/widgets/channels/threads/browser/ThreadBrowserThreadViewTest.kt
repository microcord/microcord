package com.discord.widgets.channels.threads.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadBrowserThreadViewTest {
    @Test
    fun bucketsActivityTimestampsAtReferenceBoundaries() {
        val now = 4_000_000_000L
        fun bucket(age: Long) = activityTimestampBucket(now - age, now)

        assertEquals("Minutes(count=1)", bucket(59_999).toString())
        assertEquals("Minutes(count=1)", bucket(60_000).toString())
        assertEquals("Hours(count=1)", bucket(3_600_000).toString())
        assertEquals("Days(count=1)", bucket(86_400_000).toString())
        assertEquals("MoreThanMonth", bucket(2_592_000_000).toString())
    }
}
