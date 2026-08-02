package com.discord.utilities.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpressionFrecencyTrackerTest {
    @Test
    fun lifetimeFrequencyIsWeightedByRecentUseBands() {
        val tracker = ExpressionFrecencyTracker()
        val now = 100L * DAY
        tracker.track("recent", now)
        repeat(5) { tracker.track("frequent", now - 40L * DAY) }

        assertEquals(listOf("frequent", "recent"), tracker.getSortedKeys(now))
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
