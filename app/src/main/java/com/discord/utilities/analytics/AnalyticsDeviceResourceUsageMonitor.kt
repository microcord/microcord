package com.discord.utilities.analytics

import com.discord.utilities.system.DeviceResourceUsageMonitor
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeSpan
import java.util.concurrent.TimeUnit

object AnalyticsDeviceResourceUsageMonitor {
    private val resourceMonitor = DeviceResourceUsageMonitor(
        TimeSpan(1, TimeUnit.SECONDS),
        ClockFactory.get(),
        ::publish,
    )

    internal fun publish(usage: DeviceResourceUsageMonitor.ResourceUsage) {
        AnalyticSuperProperties.setClientPerformanceProperties(
            usage.cpuUsagePercent,
            usage.memoryRssBytes / 1_024,
            usage.cpuCoreCount,
        )
    }

    fun start() = resourceMonitor.start()
}
