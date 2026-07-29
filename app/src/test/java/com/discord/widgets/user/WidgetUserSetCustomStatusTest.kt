package com.discord.widgets.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.discord.utilities.time.TimeUtils

class WidgetUserSetCustomStatusTest {
    private val now = 1_721_886_300_123L // 2024-07-25T05:45:00.123Z

    @Test fun expirationIsUtcAndExact() {
        assertNull(WidgetUserSetCustomStatus.expirationUtc(WidgetUserSetCustomStatus.Expiration.NEVER, now))
        assertEquals("2024-07-25T06:15:00.123Z", WidgetUserSetCustomStatus.expirationUtc(WidgetUserSetCustomStatus.Expiration.IN_30_MINUTES, now))
        assertEquals("2024-07-25T06:45:00.123Z", WidgetUserSetCustomStatus.expirationUtc(WidgetUserSetCustomStatus.Expiration.IN_1_HOUR, now))
        assertEquals("2024-07-25T09:45:00.123Z", WidgetUserSetCustomStatus.expirationUtc(WidgetUserSetCustomStatus.Expiration.IN_4_HOURS, now))
        assertEquals("2024-07-26T00:00:00.000Z", WidgetUserSetCustomStatus.expirationUtc(WidgetUserSetCustomStatus.Expiration.TOMORROW, now))

        assertNull(WidgetUserSetCustomStatusViewModel.expirationUtc(WidgetUserSetCustomStatusViewModel.FormState.Expiration.NEVER, now))
        assertEquals("2024-07-25T06:15:00", WidgetUserSetCustomStatusViewModel.expirationUtc(WidgetUserSetCustomStatusViewModel.FormState.Expiration.IN_30_MINUTES, now))
        assertEquals("2024-07-25T06:45:00", WidgetUserSetCustomStatusViewModel.expirationUtc(WidgetUserSetCustomStatusViewModel.FormState.Expiration.IN_1_HOUR, now))
        assertEquals("2024-07-25T09:45:00", WidgetUserSetCustomStatusViewModel.expirationUtc(WidgetUserSetCustomStatusViewModel.FormState.Expiration.IN_4_HOURS, now))
        val tomorrow = TimeUtils.toCalendar(now).apply {
            add(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        assertEquals(
            TimeUtils.toUTCDateTime(tomorrow.timeInMillis),
            WidgetUserSetCustomStatusViewModel.expirationUtc(WidgetUserSetCustomStatusViewModel.FormState.Expiration.TOMORROW, now),
        )
    }

    @Test fun formTrimsLimitsAndClearsEmptyStatus() {
        val long = "x".repeat(200)
        val saved = WidgetUserSetCustomStatus.submission(WidgetUserSetCustomStatus.Form(long, WidgetUserSetCustomStatus.Expiration.NEVER), now)
        assertEquals(128, saved.text?.length)
        assertNull(saved.expiresAtUtc)
        assertEquals(WidgetUserSetCustomStatus.Submission(null, null, null), WidgetUserSetCustomStatus.submission(WidgetUserSetCustomStatus.Form("  "), now))
    }

    @Test fun fragmentClassesCompile() {
        assertEquals("WidgetUserSheet", com.discord.widgets.user.usersheet.WidgetUserSheet::class.java.simpleName)
        assertEquals("WidgetUserMutualFriends", WidgetUserMutualFriends::class.java.simpleName)
        assertEquals("WidgetUserMutualGuilds", WidgetUserMutualGuilds::class.java.simpleName)
        assertEquals("WidgetUserStatusSheet", WidgetUserStatusSheet::class.java.simpleName)
    }
}
