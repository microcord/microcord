package com.discord.widgets.servers.guild_role_subscription.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EarningMetricsTest {
    @Test fun `126 21 earnings defaults preserve unknown dates and empty history`() {
        val current = CurrentMonthEarningMetrics()
        val metrics = TotalEarningMetrics(current, currentPeriod = null)

        assertEquals(0, current.revenue)
        assertEquals(0, current.subscriberCount)
        assertEquals("", current.nextPaymentDate)
        assertEquals("", current.revenueSinceDate)
        assertNull(current.monthOverMonthRevenueChangePercent)
        assertNull(metrics.currentPeriod)
        assertEquals(emptyList<TotalPayoutsForPeriod>(), metrics.previousPeriods)
    }
}
