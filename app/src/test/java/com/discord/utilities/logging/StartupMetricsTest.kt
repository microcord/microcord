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
    fun summaryListsRestoreStepsAndSlowestDecodesFirst() {
        val report = formatStartupSummary(
            listOf(
                StartupMetrics.Entry(StartupMetrics.Kind.BOOT, BootLogEvent.CACHE_RESTORE_STARTED.line, 100.0),
                StartupMetrics.Entry(StartupMetrics.Kind.RESTORE, "guilds.restore", 110.0, waitMs = 30.0, durationMs = 40.0),
                StartupMetrics.Entry(StartupMetrics.Kind.RESTORE, "channels.init", 150.0, waitMs = 0.0, durationMs = 5.0),
                StartupMetrics.Entry(StartupMetrics.Kind.DECODE, "SMALL", 90.0, durationMs = 1.0, bytes = 1024),
                StartupMetrics.Entry(StartupMetrics.Kind.DECODE, "BIG", 95.0, durationMs = 25.0, bytes = 2048),
            ),
            uptimeMs = 400.0,
        )

        assertTrue(report.contains("Queued before first step: 10.000 ms"))
        assertTrue(report.contains("Total: 45.000 ms, waiting on cache reads: 30.000 ms"))
        assertTrue(report.indexOf("  BIG") < report.indexOf("  SMALL"))
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
