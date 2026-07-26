package com.discord.widgets.guildcommunicationdisabled

import com.discord.widgets.guildcommunicationdisabled.start.GuildCommunicationDisabledDateUtils
import com.discord.widgets.guildcommunicationdisabled.start.TimeDurationDisabledCommunication
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeoutDurationsTest {

    /**
     * The Vineflower decompile lists these alphabetically; the real ordinals come from the
     * synthetic `$VALUES` array. Getting the order wrong silently reorders the radio group and
     * mislabels every row, so it is pinned here.
     */
    @Test
    fun `durations are in the reference ordinal order`() {
        assertEquals(
            listOf(
                TimeDurationDisabledCommunication.SECONDS_60,
                TimeDurationDisabledCommunication.MINUTES_5,
                TimeDurationDisabledCommunication.MINUTES_10,
                TimeDurationDisabledCommunication.HOUR_1,
                TimeDurationDisabledCommunication.DAY_1,
                TimeDurationDisabledCommunication.WEEK_1,
            ),
            TimeDurationDisabledCommunication.values().toList(),
        )
    }

    @Test
    fun `durations carry the reference millisecond values`() {
        assertEquals(
            listOf(60_000, 300_000, 600_000, 3_600_000, 86_400_000, 604_800_000),
            TimeDurationDisabledCommunication.values().map { it.durationMs },
        )
    }

    /** Without a context the reference falls back to these English literals rather than throwing. */
    @Test
    fun `friendly duration falls back to the reference literals without a context`() {
        assertEquals(
            listOf("60 Seconds", "5 Minutes", "10 Minutes", "1 Hour", "1 Day", "1 Week"),
            TimeDurationDisabledCommunication.values().map {
                GuildCommunicationDisabledDateUtils.getFriendlyDurationString(null, it).toString()
            },
        )
    }

    /**
     * The payload is `yyyy-MM-dd'T'HH:mm:ss` in UTC with a literal `.000Z` appended — the API
     * rejects anything else, and the millis half is what the confirmation copy counts down from.
     */
    @Test
    fun `future timestamp is UTC ISO-8601 with a literal millisecond suffix`() {
        val before = System.currentTimeMillis()
        val (timestamp, millis) = GuildCommunicationDisabledDateUtils.getFutureTimestamp(
            TimeDurationDisabledCommunication.HOUR_1.durationMs,
        )
        val after = System.currentTimeMillis()

        assertTrue(timestamp, timestamp.endsWith(".000Z"))
        assertTrue(millis >= before + 3_600_000)
        assertTrue(millis <= after + 3_600_000)

        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val parsed = requireNotNull(parser.parse(timestamp)).time
        // Formatting drops sub-second precision, so allow one second of slack.
        assertTrue(Math.abs(parsed - millis) < 1_000)
    }
}
