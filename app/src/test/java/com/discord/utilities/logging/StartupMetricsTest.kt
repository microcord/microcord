package com.discord.utilities.logging

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class StartupMetricsTest {
    @Test
    fun summarySeparatesReadyFromOrdinaryGatewayFrames() {
        val report = formatStartupSummary(
            listOf(
                StartupMetrics.Entry(StartupMetrics.Kind.BOOT, "runtime ready", 100.0),
                StartupMetrics.Entry(StartupMetrics.Kind.GATEWAY, "HELLO", 200.0, 50.0, 1.0, 2.0),
                StartupMetrics.Entry(StartupMetrics.Kind.GATEWAY, "READY", 300.0, 100.0, 10.0, 20.0),
            ),
            uptimeMs = 400.0,
        )

        assertTrue(report.contains("READY wait: 100.000 ms"))
        assertTrue(report.contains("READY parse: 20.000 ms"))
        assertTrue(report.contains("Ordinary parse avg: 2.000 ms"))
    }

    @Test
    fun gatewayFramesStopAfterReady() {
        StartupMetrics.beginProcess("test")
        StartupMetrics.recordGatewayFrame("READY", 0L, null, 0L, 0L)
        StartupMetrics.recordGatewayFrame("MESSAGE_CREATE", 0L, null, 0L, 0L)

        assertTrue(StartupMetrics.snapshot().any { it.name == "READY" })
        assertFalse(StartupMetrics.snapshot().any { it.name == "MESSAGE_CREATE" })
    }
}
