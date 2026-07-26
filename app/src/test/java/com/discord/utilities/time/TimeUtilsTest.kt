package com.discord.utilities.time

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimeUtilsTest {

    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    /** 2021-06-11 15:42:07 UTC. */
    private val referenceNow = 1_623_426_127_000L

    private val fixedClock = Clock { referenceNow }

    @Before
    fun setUp() {
        // Pin the zone so "today" boundaries and rendered clock times are deterministic.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    // region wire format

    @Test
    fun `parses API timestamps as UTC`() {
        assertEquals(referenceNow, TimeUtils.parseUTCDate("2021-06-11T15:42:07"))
    }

    @Test
    fun `absent or malformed timestamps parse to zero`() {
        assertEquals(0L, TimeUtils.parseUTCDate(null))
        assertEquals(0L, TimeUtils.parseUTCDate("not a date"))
        assertEquals(0L, TimeUtils.parseUTCDate(""))
    }

    @Test
    fun `renders epoch millis back to the API format`() {
        assertEquals("2021-06-11T15:42:07", TimeUtils.toUTCDateTime(referenceNow))
        assertEquals("2021-06-11", TimeUtils.toUTCDateTime(referenceNow, TimeUtils.UTCFormat.SHORT))
        assertNull(TimeUtils.toUTCDateTime(null))
    }

    @Test
    fun `round trips through the API format`() {
        val rendered = TimeUtils.toUTCDateTime(referenceNow)
        assertEquals(referenceNow, TimeUtils.parseUTCDate(rendered))
    }

    @Test
    fun `currentTimeUTCDateString uses the supplied clock`() {
        assertEquals("2021-06-11T15:42:07", TimeUtils.currentTimeUTCDateString(fixedClock))
    }

    // endregion

    // region snowflakes

    @Test
    fun `snowflake and millis conversions are inverse at millisecond resolution`() {
        val snowflake = TimeUtils.millisToSnowflake(referenceNow)
        assertEquals(referenceNow, TimeUtils.parseSnowflake(snowflake))
    }

    @Test
    fun `a null snowflake maps to the Discord epoch`() {
        assertEquals(1_420_070_400_000L, TimeUtils.parseSnowflake(null))
    }

    // endregion

    // region date arithmetic

    @Test
    fun `addDaysToDate moves by whole days`() {
        val result = TimeUtils.addDaysToDate("2021-06-11T15:42:07", 3)
        assertEquals("2021-06-14T15:42:07", TimeUtils.toUTCDateTime(result))
    }

    @Test
    fun `addMonthsAndDaysToDate applies both offsets`() {
        assertEquals(
            "2021-08-13T15:42:07",
            TimeUtils.addMonthsAndDaysToDate("2021-06-11T15:42:07", months = 2, days = 2),
        )
    }

    @Test
    fun `getMonthsBetweenDates counts whole calendar months in either order`() {
        val june = java.util.Date(TimeUtils.parseUTCDate("2021-06-11T00:00:00"))
        val september = java.util.Date(TimeUtils.parseUTCDate("2021-09-11T00:00:00"))
        assertEquals(2, TimeUtils.getMonthsBetweenDates(june, september))
        assertEquals(2, TimeUtils.getMonthsBetweenDates(september, june))
    }

    @Test
    fun `millis decompose to whole units and clamp below one unit`() {
        assertEquals(0, TimeUtils.getSecondsFromMillis(999))
        assertEquals(1, TimeUtils.getSecondsFromMillis(1_000))
        assertEquals(0, TimeUtils.getMinutesFromMillis(59_999))
        assertEquals(90, TimeUtils.getMinutesFromMillis(5_400_000))
        assertEquals(1, TimeUtils.getHoursFromMillis(3_600_000))
        assertEquals(0, TimeUtils.getDaysFromMillis(86_399_999))
        assertEquals(2, TimeUtils.getDaysFromMillis(172_800_000))
    }

    // endregion

    // region chat-row timestamps

    private fun at(offsetMillis: Long) = referenceNow + offsetMillis

    private fun renderEN(timestamp: Long, locale: Locale = Locale.US) =
        TimeUtils.toReadableTimeStringEN(locale, timestamp, fixedClock)

    @Test
    fun `a timestamp earlier today renders as Today at`() {
        val rendered = renderEN(at(-2 * 60 * 60 * 1000L))
        assertTrue(rendered, rendered.startsWith("Today at "))
        assertTrue(rendered, rendered.contains("1:42"))
    }

    @Test
    fun `the first moment of today renders as Today at`() {
        val startOfToday = TimeUtils.toCalendar(referenceNow).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 1)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertTrue(renderEN(startOfToday).startsWith("Today at "))
    }

    @Test
    fun `a timestamp from yesterday renders as Yesterday at`() {
        val rendered = renderEN(at(-24 * 60 * 60 * 1000L))
        assertTrue(rendered, rendered.startsWith("Yesterday at "))
    }

    @Test
    fun `an older timestamp renders as an absolute date and time`() {
        val rendered = renderEN(at(-5L * 24 * 60 * 60 * 1000))
        assertTrue(rendered, !rendered.startsWith("Today") && !rendered.startsWith("Yesterday"))
        assertTrue(rendered, rendered.contains("2021"))
        assertTrue(rendered, rendered.contains("Jun"))
    }

    @Test
    fun `a future timestamp renders as an absolute date and time`() {
        val rendered = renderEN(at(3L * 24 * 60 * 60 * 1000))
        assertTrue(rendered, !rendered.startsWith("Today") && !rendered.startsWith("Yesterday"))
    }

    @Test
    fun `US English renders a 12-hour clock`() {
        val rendered = renderEN(at(-2 * 60 * 60 * 1000L), Locale.US)
        assertTrue(rendered, rendered.contains("1:42"))
        assertTrue(rendered, rendered.contains("PM") || rendered.contains("pm"))
    }

    @Test
    fun `UK English renders a 24-hour clock`() {
        val rendered = renderEN(at(-2 * 60 * 60 * 1000L), Locale.UK)
        assertTrue(rendered, rendered.startsWith("Today at "))
        assertTrue(rendered, rendered.contains("13:42"))
    }

    @Test
    fun `a non-English locale yields no English rendering`() {
        assertEquals("", TimeUtils.toReadableTimeStringEN(Locale.GERMANY, referenceNow, fixedClock))
        assertEquals("", TimeUtils.toReadableTimeStringEN(Locale.JAPAN, referenceNow, fixedClock))
    }

    // endregion
}

class TimeSpanTest {

    @Test
    fun `converts between units`() {
        val span = TimeSpan(90, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(1L, span.toMinutes())
        assertEquals(90_000L, span.toMillis())
        assertEquals(0L, span.toHours())
    }

    @Test
    fun `arithmetic normalises mixed units to nanoseconds`() {
        val sum = TimeSpan(1, java.util.concurrent.TimeUnit.MINUTES) +
            TimeSpan(30, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(90L, sum.toSeconds())

        val difference = TimeSpan(1, java.util.concurrent.TimeUnit.MINUTES) -
            TimeSpan(30, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(30L, difference.toSeconds())
    }
}

class TimeElapsedTest {

    @Test
    fun `latches the elapsed time on first read`() {
        var now = 1_000L
        val clock = Clock { now }
        val elapsed = TimeElapsed(clock)

        now = 3_500L
        assertEquals(2_500L, elapsed.milliseconds)

        now = 9_000L
        assertEquals(2_500L, elapsed.milliseconds)
        assertEquals(2.5f, elapsed.seconds, 0.0001f)
    }

    @Test
    fun `an explicit start time is honoured`() {
        val clock = Clock { 5_000L }
        assertEquals(4_000L, TimeElapsed(clock, startTime = 1_000L).milliseconds)
    }
}

class ClockFactoryTest {

    @Test
    fun `falls back to the device clock before init`() {
        val before = System.currentTimeMillis()
        val now = ClockFactory.get().currentTimeMillis()
        assertTrue(now >= before)
    }
}
