package com.discord.utilities.system

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
}
