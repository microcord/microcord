package com.discord.utilities.logging

import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.developer.DeveloperSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootLogTest {
    @Test
    fun verboseBootDefaultsOff() {
        SharedPreferencesProvider.setInstanceForTesting(null)
        assertFalse(DeveloperSettings.isVerboseBootEnabled())
    }

    @Test
    fun conciseStatusShowsConnectingOnlyDuringGatewayHandshake() {
        assertFalse(isGatewayConnecting(BootLogEvent.PROCESS_START.line))
        assertTrue(isGatewayConnecting(BootLogEvent.GATEWAY_DISCOVERY.line))
        assertTrue(isGatewayConnecting(BootLogEvent.GATEWAY_IDENTIFY.line))
        assertFalse(isGatewayConnecting(BootLogEvent.GATEWAY_DATA_READY.line))
    }

    @Test
    fun publishesOnlyBoundedFixedSafeEvents() {
        BootLog.resetForTest()
        var snapshot = emptyList<String>()
        val stop = BootLog.observe { snapshot = it }

        repeat(BootLog.MAX_LINES + 5) { BootLog.record(BootLogEvent.PROCESS_START) }

        assertEquals(BootLog.MAX_LINES, snapshot.size)
        BootLogEvent.values().forEach {
            assertFalse(it.line.contains(Regex("token|password|cookie|payload|credential|secret", RegexOption.IGNORE_CASE)))
        }
        stop()
        BootLog.resetForTest()
    }
}
