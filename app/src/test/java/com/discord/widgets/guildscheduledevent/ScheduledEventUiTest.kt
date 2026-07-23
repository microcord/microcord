package com.discord.widgets.guildscheduledevent

import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduledEventUiTest {
    @Test
    fun timingPreservesReferenceBoundaries() {
        val now = 1_000_000_000L
        assertEquals(
            GuildScheduledEventTiming.LIVE,
            ScheduledEventUi.timing(now, GuildScheduledEventStatus.ACTIVE, now),
        )
        assertEquals(
            GuildScheduledEventTiming.EXPIRED,
            ScheduledEventUi.timing(now - 60 * 60_000L, GuildScheduledEventStatus.SCHEDULED, now),
        )
        assertEquals(
            GuildScheduledEventTiming.NOW,
            ScheduledEventUi.timing(now, GuildScheduledEventStatus.SCHEDULED, now),
        )
        assertEquals(
            GuildScheduledEventTiming.SOON,
            ScheduledEventUi.timing(now + 15 * 60_000L, GuildScheduledEventStatus.SCHEDULED, now),
        )
    }
}
