package com.discord.widgets.user.search

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class ChannelFrecencyTrackerTest {

    private val now = 1_700_000_000_000L
    private val week = TimeUnit.DAYS.toMillis(7)

    @Test
    fun ranksTheMostVisitedChannelFirst() {
        val tracker = ChannelFrecencyTracker()
        tracker.track(1L, now - TimeUnit.HOURS.toMillis(1))
        tracker.track(2L, now - TimeUnit.HOURS.toMillis(2))
        tracker.track(2L, now - TimeUnit.HOURS.toMillis(3))

        assertEquals(listOf(2L, 1L), tracker.getSortedKeys(now).toList())
    }

    /** Equal counts fall back to whichever was used most recently. */
    @Test
    fun breaksTiesOnTheMoreRecentVisit() {
        val tracker = ChannelFrecencyTracker()
        tracker.track(1L, now - TimeUnit.HOURS.toMillis(5))
        tracker.track(2L, now - TimeUnit.HOURS.toMillis(1))

        assertEquals(listOf(2L, 1L), tracker.getSortedKeys(now).toList())
    }

    @Test
    fun visitsOlderThanAWeekAreEvicted() {
        val tracker = ChannelFrecencyTracker()
        tracker.track(1L, now - week - 1)
        tracker.track(2L, now - TimeUnit.HOURS.toMillis(1))

        assertEquals(listOf(2L), tracker.getSortedKeys(now).toList())
    }

    @Test
    fun onlyTheLastTenVisitsCount() {
        val tracker = ChannelFrecencyTracker()
        repeat(12) { index -> tracker.track(1L, now - TimeUnit.MINUTES.toMillis(index.toLong())) }
        tracker.track(2L, now)

        // Ten visits at 100 each; the eleventh and twelfth are dropped by the sample cap.
        assertEquals(listOf(1L, 2L), tracker.getSortedKeys(now).toList())
    }
}
