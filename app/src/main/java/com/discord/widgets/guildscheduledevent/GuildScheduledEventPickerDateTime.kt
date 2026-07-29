package com.discord.widgets.guildscheduledevent

import com.discord.api.utcdatetime.UtcDateTime
import com.discord.utilities.time.TimeUtils
import java.util.Calendar

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

    fun generateAppropriateStartDateTime(): Pair<GuildScheduledEventPickerDate, GuildScheduledEventPickerTime> =
        Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 2) }.toPickerDateTime()

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
