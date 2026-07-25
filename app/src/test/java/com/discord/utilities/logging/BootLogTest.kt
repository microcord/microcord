package com.discord.utilities.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BootLogTest {
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
