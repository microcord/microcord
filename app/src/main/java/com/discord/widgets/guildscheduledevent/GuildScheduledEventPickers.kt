package com.discord.widgets.guildscheduledevent

import com.discord.api.utcdatetime.UtcDateTime
import com.discord.utilities.time.TimeUtils
import java.io.Serializable
import java.util.Calendar

/**
 * A calendar day as the create/edit form holds it, before it is combined with a
 * [GuildScheduledEventPickerTime] and converted to a UTC instant.
 *
 * [month] is zero-based, matching [Calendar.MONTH] and `DatePickerDialog`.
 */
data class GuildScheduledEventPickerDate(
    val year: Int,
    val month: Int,
    val dayOfMonth: Int,
) : Serializable {

    /** Midnight local time on this day. Used only for date-only comparisons. */
    fun toMillis(): Long = Calendar.getInstance().apply {
        set(year, month, dayOfMonth, 0, 0)
    }.timeInMillis

    companion object {
        fun now(): GuildScheduledEventPickerDate = Calendar.getInstance().let {
            GuildScheduledEventPickerDate(
                it.get(Calendar.YEAR),
                it.get(Calendar.MONTH),
                it.get(Calendar.DAY_OF_MONTH),
            )
        }
    }
}

/** A wall-clock time of day as the create/edit form holds it. */
data class GuildScheduledEventPickerTime(
    val hourOfDay: Int,
    val minute: Int,
    val second: Int,
) : Serializable

/**
 * Conversions between the form's split date/time pair and the wire's UTC instants.
 *
 * All of these run against the device's default time zone deliberately: the user picks a local
 * date and time, and only [toUtcDateString] converts to the API's UTC representation.
 */
object GuildScheduledEventPickerDateTime {

    private fun Calendar.toPickerDateTime(): Pair<GuildScheduledEventPickerDate, GuildScheduledEventPickerTime> =
        GuildScheduledEventPickerDate(
            get(Calendar.YEAR),
            get(Calendar.MONTH),
            get(Calendar.DAY_OF_MONTH),
        ) to GuildScheduledEventPickerTime(
            get(Calendar.HOUR_OF_DAY),
            get(Calendar.MINUTE),
            get(Calendar.SECOND),
        )

    fun fromUtcDateTime(
        utcDateTime: UtcDateTime,
    ): Pair<GuildScheduledEventPickerDate, GuildScheduledEventPickerTime> =
        Calendar.getInstance().apply { timeInMillis = utcDateTime.dateTimeMillis }.toPickerDateTime()

    /** The default start offered by the create form: two hours from now. */
    fun generateAppropriateStartDateTime(): Pair<GuildScheduledEventPickerDate, GuildScheduledEventPickerTime> =
        Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 2) }.toPickerDateTime()

    /** The default end offered once a start is chosen: one hour after it. */
    fun generateDefaultEndDateTime(
        startDate: GuildScheduledEventPickerDate,
        startTime: GuildScheduledEventPickerTime,
    ): Pair<GuildScheduledEventPickerDate, GuildScheduledEventPickerTime> =
        Calendar.getInstance().apply {
            set(
                startDate.year,
                startDate.month,
                startDate.dayOfMonth,
                startTime.hourOfDay,
                startTime.minute,
                startTime.second,
            )
            add(Calendar.HOUR_OF_DAY, 1)
        }.toPickerDateTime()

    /** Milliseconds are cleared so two equal form values always produce the same instant. */
    fun toMillis(
        date: GuildScheduledEventPickerDate,
        time: GuildScheduledEventPickerTime,
    ): Long = Calendar.getInstance().apply {
        set(date.year, date.month, date.dayOfMonth, time.hourOfDay, time.minute, time.second)
        clear(Calendar.MILLISECOND)
    }.timeInMillis

    fun toUtcDateString(
        date: GuildScheduledEventPickerDate,
        time: GuildScheduledEventPickerTime,
    ): String = TimeUtils.toUTCDateTime(toMillis(date, time)).orEmpty()
}
