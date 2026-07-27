package com.discord.utilities.system

import com.discord.utilities.analytics.AnalyticSuperProperties
import com.discord.utilities.analytics.AnalyticsDeviceResourceUsageMonitor
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceResourceUsageMonitorTest {
    @Test
    fun `resource usage normalizes cpu and converts rss pages to bytes`() {
        assertEquals(
            DeviceResourceUsageMonitor.ResourceUsage(
                cpuUsagePercent = 50,
                memoryRssBytes = 102_400,
                cpuCoreCount = 4,
            ),
            DeviceResourceUsageMonitor.calculateResourceUsage(
                previousTotalTime = 1_000,
                currentTotalTime = 1_200,
                rssPages = 25,
                elapsedSeconds = 1.0,
                clockTicksPerSecond = 100,
                pageSize = 4_096,
                coreCount = 4,
            ),
        )
        assertEquals(
            1,
            DeviceResourceUsageMonitor.calculateResourceUsage(
                previousTotalTime = 0,
                currentTotalTime = 100,
                rssPages = 0,
                elapsedSeconds = 1.0,
                clockTicksPerSecond = 100,
                pageSize = 4_096,
                coreCount = 0,
            ).cpuCoreCount,
        )
    }

    @Test
    fun `analytics publishes cpu rss kibibytes and core count without inventing an advertiser id`() {
        val advertiserIdBefore = AnalyticSuperProperties.superProperties["device_advertiser_id"]

        AnalyticsDeviceResourceUsageMonitor.publish(
            DeviceResourceUsageMonitor.ResourceUsage(
                cpuUsagePercent = 37,
                memoryRssBytes = 8_193,
                cpuCoreCount = 6,
            ),
        )

        assertEquals(37, AnalyticSuperProperties.superProperties["client_performance_cpu"])
        assertEquals(8L, AnalyticSuperProperties.superProperties["client_performance_memory"])
        assertEquals(6, AnalyticSuperProperties.superProperties["cpu_core_count"])
        assertEquals(advertiserIdBefore, AnalyticSuperProperties.superProperties["device_advertiser_id"])
    }
}
