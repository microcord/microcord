package com.discord.utilities.time

import android.content.Context
import android.text.format.DateUtils
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.locale.LocaleManager
import java.text.DateFormat
import java.text.DateFormatSymbols
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Formatting and arithmetic for the two time representations the API uses: ISO-8601 UTC strings
 * and snowflake ids.
 *
 * All parsing pins [Locale.ROOT] and the UTC zone because API timestamps are locale-independent;
 * only rendering uses the user's locale.
 */
object TimeUtils {

    /** The wire format Discord uses for timestamps. */
    object UTCFormat {
        const val LONG = "yyyy-MM-dd'T'HH:mm:ss"
        const val SHORT = "yyyy-MM-dd"
    }

    private const val DATE_FORMAT_DISCORD_API = UTCFormat.LONG


    private const val MILLIS_PER_SECOND = 1_000L
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MILLIS_PER_HOUR = 3_600_000L
    private const val MILLIS_PER_DAY = 86_400_000L
    private const val MILLIS_PER_WEEK = 604_800_000L

    private val localeManager = LocaleManager()

    // region parsing

    /** @return the epoch millis for an API timestamp, or 0 when it is absent or malformed. */
    @JvmStatic
    fun parseUTCDate(dateTime: String?): Long {
        if (dateTime == null) return 0L
        return try {
            val format = SimpleDateFormat(DATE_FORMAT_DISCORD_API, Locale.ROOT)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.parse(dateTime)?.time ?: 0L
        } catch (e: ParseException) {
            0L
        } catch (e: NumberFormatException) {
            0L
        } catch (e: ArrayIndexOutOfBoundsException) {
            0L
        }
    }

    @JvmStatic
    @JvmOverloads
    fun toUTCDateTime(unixTimeMillis: Long?, format: String = DATE_FORMAT_DISCORD_API): String? {
        val dateFormat = SimpleDateFormat(format, Locale.ROOT)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        return unixTimeMillis?.let { dateFormat.format(Date(it)) }
    }

    @JvmStatic
    fun currentTimeUTCDateString(clock: Clock): String? = toUTCDateTime(clock.currentTimeMillis())

    @JvmStatic
    fun toCalendar(unixTimeMillis: Long): Calendar =
        Calendar.getInstance(Locale.ROOT).apply { timeInMillis = unixTimeMillis }

    /**
     * @return the creation time of a snowflake in epoch millis.
     *
     * Deliberately **not** [SnowflakeUtils.toTimestamp]. The reference has both, and they are not the
     * same function: `javap -c` shows `TimeUtils.parseSnowflake` emitting `lshr` (signed) where
     * `SnowflakeUtils.toTimestamp` emits `lushr`. The two agree for every real snowflake and differ
     * only for a negative id — which `SnowflakeUtils.atPreviousMillisecond` can produce. Only the
     * epoch constant is shared.
     */
    @JvmStatic
    fun parseSnowflake(snowflake: Long?): Long =
        ((snowflake ?: 0L) shr 22) + SnowflakeUtils.DISCORD_EPOCH

    /** @return the lowest snowflake that could have been created at [millis]. */
    @JvmStatic
    fun millisToSnowflake(millis: Long): Long = SnowflakeUtils.fromTimestamp(millis)

    /** Short month, day and year in the reader's locale — "Jan 5, 2015" in en-US. */
    @JvmStatic
    fun toMediumDate(unixTimeMillis: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(unixTimeMillis))

    // endregion

    // region date arithmetic

    @JvmStatic
    fun addDaysToDate(date: String, days: Int): Long =
        toCalendar(parseUTCDate(date)).apply { add(Calendar.DAY_OF_MONTH, days) }.timeInMillis

    @JvmStatic
    @JvmOverloads
    fun addMonthsAndDaysToDate(date: String, months: Int = 0, days: Int = 0): String? {
        val calendar = toCalendar(parseUTCDate(date))
        calendar.add(Calendar.DAY_OF_MONTH, days)
        calendar.add(Calendar.MONTH, months)
        return toUTCDateTime(calendar.timeInMillis)
    }

    /**
     * Whole months between two dates, in either order.
     *
     * Counting by calendar month rather than dividing by an average month length is what makes
     * billing periods land on the same day of the month.
     */
    @JvmStatic
    fun getMonthsBetweenDates(firstDate: Date, secondDate: Date): Int {
        val calendar = Calendar.getInstance()
        val later: Date
        if (firstDate.before(secondDate)) {
            calendar.time = firstDate
            later = secondDate
        } else {
            calendar.time = secondDate
            later = firstDate
        }
        var months = 0
        while (calendar.time.before(later)) {
            calendar.add(Calendar.MONTH, 1)
            months += 1
        }
        return months - 1
    }

    fun getDaysFromMillis(millis: Long): Int =
        if (millis < MILLIS_PER_DAY) 0 else (millis / MILLIS_PER_DAY).toInt()

    fun getHoursFromMillis(millis: Long): Int =
        if (millis < MILLIS_PER_HOUR) 0 else (millis / MILLIS_PER_HOUR).toInt()

    fun getMinutesFromMillis(millis: Long): Int =
        if (millis < MILLIS_PER_MINUTE) 0 else (millis / MILLIS_PER_MINUTE).toInt()

    fun getSecondsFromMillis(millis: Long): Int =
        if (millis < MILLIS_PER_SECOND) 0 else (millis / MILLIS_PER_SECOND).toInt()

    // endregion

    // region rendering

    /**
     * The chat-row timestamp: "Today at 3:42 PM", "Yesterday at 3:42 PM", or an absolute date and
     * time for anything older.
     *
     * The friendly form is only produced for English locales because the wording is not
     * translated; every other locale falls back to the platform's localised relative formatter.
     */
    @JvmStatic
    @JvmOverloads
    fun toReadableTimeString(
        context: Context,
        timestampMs: Long,
        clock: Clock = ClockFactory.get(),
    ): CharSequence {
        val locale = localeManager.getPrimaryLocale(context)
        if (locale.language == "en") {
            return toReadableTimeStringEN(locale, timestampMs, clock)
        }
        return DateUtils.getRelativeDateTimeString(
            context,
            timestampMs,
            MILLIS_PER_DAY,
            MILLIS_PER_WEEK,
            DateUtils.FORMAT_NUMERIC_DATE,
        )
    }

    @JvmStatic
    fun getReadableTimeString(context: Context, utcDate: String?): String =
        toReadableTimeString(context, parseUTCDate(utcDate)).toString()

    /**
     * English-only "Today at .../Yesterday at ..." rendering.
     *
     * Boundaries are computed from the start of the *next* local day and walked backwards, so a
     * timestamp is "today" purely by calendar date rather than by being less than 24h old.
     *
     * @return an empty string when [locale] is not English, mirroring the caller's guard.
     */
    @JvmOverloads
    fun toReadableTimeStringEN(
        locale: Locale,
        unixTimeMillis: Long,
        clock: Clock = ClockFactory.get(),
    ): String {
        if (locale.language != "en") return ""

        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT, locale)
        val dateTimeFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)

        val boundary = toCalendar(clock.currentTimeMillis())
        boundary.set(Calendar.HOUR_OF_DAY, 0)
        boundary.set(Calendar.MINUTE, 0)
        boundary.set(Calendar.SECOND, 0)
        boundary.set(Calendar.MILLISECOND, 0)

        boundary.add(Calendar.DAY_OF_MONTH, 1)
        if (unixTimeMillis > boundary.timeInMillis) return dateTimeFormat.format(unixTimeMillis)

        boundary.add(Calendar.DAY_OF_MONTH, -1)
        if (unixTimeMillis > boundary.timeInMillis) return "Today at ${timeFormat.format(unixTimeMillis)}"

        boundary.add(Calendar.DAY_OF_MONTH, -1)
        if (unixTimeMillis > boundary.timeInMillis) {
            return "Yesterday at ${timeFormat.format(unixTimeMillis)}"
        }

        return dateTimeFormat.format(unixTimeMillis)
    }

    /** Localised full month name for a 1-based month number. */
    fun getLocalizedMonthName(monthInt: Int, context: Context): String =
        DateFormatSymbols(localeManager.getPrimaryLocale(context)).months[monthInt - 1]

    @JvmOverloads
    fun renderUtcDate(unixTimeMillis: Long, context: Context, outputDateFormat: Int = DateFormat.MEDIUM): String =
        DateFormat.getDateInstance(outputDateFormat, localeManager.getPrimaryLocale(context))
            .format(unixTimeMillis)

    @JvmOverloads
    fun renderUtcDate(
        utcDate: String,
        context: Context,
        inputFormat: String = DATE_FORMAT_DISCORD_API,
        outputFormat: DateFormat = android.text.format.DateFormat.getMediumDateFormat(context),
        offsetDays: Int = 0,
    ): String = renderUtcDate(
        utcDate,
        localeManager.getPrimaryLocale(context),
        inputFormat,
        outputFormat,
        offsetDays,
    )

    @JvmOverloads
    fun renderUtcDate(
        utcDate: String,
        locale: Locale,
        inputFormat: String = DATE_FORMAT_DISCORD_API,
        outputFormat: DateFormat,
        offsetDays: Int = 0,
    ): String {
        val parser = SimpleDateFormat(inputFormat, locale)
        parser.timeZone = TimeZone.getTimeZone("UTC")
        val calendar = Calendar.getInstance(locale)
        calendar.time = parser.parse(utcDate) ?: Date()
        calendar.add(Calendar.DAY_OF_MONTH, offsetDays)
        return outputFormat.format(calendar.time)
    }

    @JvmOverloads
    fun renderUtcDateTime(
        utcDateTime: String,
        context: Context,
        inputFormat: String = DATE_FORMAT_DISCORD_API,
        outputDateFormat: Int,
        outputTimeFormat: Int,
    ): String {
        val parser = SimpleDateFormat(inputFormat, localeManager.getPrimaryLocale(context))
        parser.timeZone = TimeZone.getTimeZone("UTC")
        val date = parser.parse(utcDateTime) ?: Date()
        return DateFormat.getDateTimeInstance(outputDateFormat, outputTimeFormat).format(date)
    }

    /**
     * Countdown-style rendering of the distance between two instants.
     *
     * Under a day the elapsed time is shown as a running clock (slow mode, call duration); beyond
     * that the platform's relative span string is used instead.
     */
    @JvmOverloads
    fun toFriendlyString(
        startTimeMs: Long,
        targetTimestampMs: Long = ClockFactory.get().currentTimeMillis(),
        formatString: String? = null,
        argName: String? = null,
    ): CharSequence {
        val deltaMs = targetTimestampMs - startTimeMs
        if (TimeUnit.MILLISECONDS.toDays(deltaMs) == 0L) {
            return toFriendlyStringSimple(deltaMs, formatString, argName)
        }
        return DateUtils.getRelativeTimeSpanString(
            startTimeMs,
            targetTimestampMs,
            MILLIS_PER_DAY,
            DateUtils.FORMAT_NUMERIC_DATE,
        )
    }

    /**
     * Elapsed time as `H:MM:SS`, optionally interpolated into [formatString] at `{argName}`.
     * Negative deltas clamp to zero so a clock skew never renders a negative countdown.
     */
    @JvmOverloads
    fun toFriendlyStringSimple(
        deltaTimeMs: Long,
        formatString: String? = null,
        argName: String? = null,
    ): CharSequence {
        val elapsedTime = DateUtils.formatElapsedTime(
            TimeUnit.MILLISECONDS.toSeconds(max(0L, deltaTimeMs)),
        )
        if (formatString != null && argName != null) {
            return formatString.replace("{$argName}", elapsedTime)
        }
        return elapsedTime
    }

    // endregion
}
