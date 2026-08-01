package com.discord.workers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FirstPartyWorkersTest {
    @Test fun callActionPreservesAuthConnectivityAndThreeAttemptPolicy() {
        assertEquals(WorkerOutcome.FAILURE, call(authenticated = false))
        assertEquals(WorkerOutcome.RETRY, call(connected = false, attempt = 99))
        assertEquals(WorkerOutcome.RETRY, call(actionResult = false, attempt = 2))
        assertEquals(WorkerOutcome.FAILURE, call(actionResult = false, attempt = 3))
        assertEquals(WorkerOutcome.SUCCESS, call())
        assertEquals("com.discord.intent.extra.EXTRA_CHANNEL_ID", CallActionWorker.KEY_CHANNEL_ID)
        assertEquals("com.discord.intent.extra.EXTRA_MESSAGE_ID", CallActionWorker.KEY_MESSAGE_ID)
    }

    @Test fun messageSendPreservesNoContentAndOfflineSemantics() {
        var called = false
        assertEquals(WorkerOutcome.SUCCESS, message(content = null) { _, _, _, _, _ -> called = true; true })
        assertFalse(called)
        assertEquals(WorkerOutcome.RETRY, message(connected = false, attempt = 2))
        assertEquals(WorkerOutcome.FAILURE, message(connected = false, attempt = 3))
        assertEquals(WorkerOutcome.FAILURE, message(currentGeneration = false))
        assertEquals(WorkerOutcome.FAILURE, message(channelId = -1))
        assertEquals(WorkerOutcome.FAILURE, message(content = " "))
        assertEquals(WorkerOutcome.FAILURE, message(content = "x".repeat(2_001)))
        assertEquals(WorkerOutcome.SUCCESS, message())
        assertEquals("MESSAGE_CONTENT", MessageSendWorker.KEY_MESSAGE_CONTENT)
        assertEquals("com.discord.intent.extra.EXTRA_STICKER_ID", MessageSendWorker.KEY_STICKER_ID)
    }

    @Test fun timedMuteSkipsExpiredJobsAndRetriesFailuresThreeTimes() {
        var called = false
        assertEquals(WorkerOutcome.SUCCESS, timed(until = 9, now = 10) { _, _, _ -> called = true; true })
        assertFalse(called)
        assertEquals(WorkerOutcome.FAILURE, timed(guildId = -1))
        assertEquals(WorkerOutcome.RETRY, timed(connected = false, attempt = 99))
        assertEquals(WorkerOutcome.RETRY, timed(actionResult = false, attempt = 2))
        assertEquals(WorkerOutcome.FAILURE, timed(actionResult = false, attempt = 3))
        assertEquals(WorkerOutcome.SUCCESS, timed())
        assertEquals("com.discord.intent.extra.EXTRA_UNTIL_TIMESTAMP_MS", TimedMuteWorker.KEY_UNTIL_TIMESTAMP_MS)
    }

    private fun call(
        authenticated: Boolean = true,
        connected: Boolean = true,
        attempt: Int = 0,
        actionResult: Boolean = true,
    ) = CallActionWorker.execute(authenticated, connected, 1, 2, attempt) { _, _ -> actionResult }

    private fun message(
        authenticated: Boolean = true,
        connected: Boolean = true,
        currentGeneration: Boolean = true,
        channelId: Long = 1,
        content: String? = "hello",
        attempt: Int = 0,
        action: (Long, String, String, String?, Long) -> Boolean = { _, _, _, _, _ -> true },
    ) = MessageSendWorker.execute(
        authenticated, connected, currentGeneration, channelId, "channel", content, null, -1, attempt, action,
    )

    private fun timed(
        authenticated: Boolean = true,
        connected: Boolean = true,
        guildId: Long = 1,
        until: Long = 10,
        now: Long = 9,
        attempt: Int = 0,
        actionResult: Boolean = true,
        action: ((Long, Long, Long) -> Boolean)? = null,
    ) = TimedMuteWorker.execute(
        authenticated, connected, guildId, 2, until, now, attempt,
        action ?: { _, _, _ -> actionResult },
    )
}
