package com.discord.widgets.guildscheduledevent

import android.content.Context
import android.text.format.DateUtils
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import java.util.concurrent.TimeUnit

enum class GuildScheduledEventTiming {
    LATER, TOMORROW, TODAY, SOON, NOW, LIVE, EXPIRED;

    fun isLongStartable() = this == LATER || this == TOMORROW || this == TODAY
    fun isStartable() = this == SOON || this == NOW || this == EXPIRED
}

internal object ScheduledEventUi {
    private const val DATE_FORMAT_FLAGS = 101139

    fun timing(
        startTimeMillis: Long,
        status: GuildScheduledEventStatus,
        nowMillis: Long = System.currentTimeMillis(),
    ): GuildScheduledEventTiming {
        if (status == GuildScheduledEventStatus.ACTIVE) return GuildScheduledEventTiming.LIVE
        val minutes = TimeUnit.MILLISECONDS.toMinutes(startTimeMillis - nowMillis).toInt()
        return when {
            minutes <= -60 -> GuildScheduledEventTiming.EXPIRED
            minutes <= 0 -> GuildScheduledEventTiming.NOW
            minutes <= 15 -> GuildScheduledEventTiming.SOON
            DateUtils.isToday(startTimeMillis) -> GuildScheduledEventTiming.TODAY
            DateUtils.isToday(startTimeMillis - DateUtils.DAY_IN_MILLIS) -> GuildScheduledEventTiming.TOMORROW
            else -> GuildScheduledEventTiming.LATER
        }
    }

    fun timing(event: GuildScheduledEvent) = timing(
        event.scheduledStartTime.dateTimeMillis,
        event.status,
    )

    fun hasLiveEvent(events: Collection<GuildScheduledEvent>) =
        events.any { timing(it) == GuildScheduledEventTiming.LIVE }

    fun startingText(context: Context, event: GuildScheduledEvent) = startingText(
        context,
        event.scheduledStartTime.dateTimeMillis,
        event.entityType,
        event.status,
    )

    fun startingText(
        context: Context,
        startTimeMillis: Long,
        entityType: GuildScheduledEventEntityType,
        status: GuildScheduledEventStatus,
    ): CharSequence {
        if (status == GuildScheduledEventStatus.COMPLETED || status == GuildScheduledEventStatus.CANCELED) {
            return DateUtils.formatDateTime(context, startTimeMillis, DATE_FORMAT_FLAGS)
        }
        return when (val timing = timing(startTimeMillis, status)) {
            GuildScheduledEventTiming.LIVE -> if (entityType == GuildScheduledEventEntityType.EXTERNAL) {
                context.getString(com.discord.R.string.guild_scheduled_event_live)
            } else {
                context.getString(com.discord.R.string.stage_channel_live_now)
            }
            GuildScheduledEventTiming.NOW, GuildScheduledEventTiming.EXPIRED -> context.getString(com.discord.R.string.happening_now)
            GuildScheduledEventTiming.SOON -> {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(startTimeMillis - System.currentTimeMillis()).coerceAtLeast(1)
                context.getString(com.discord.R.string.starting_in_minutes).replace("{minutes}", minutes.toString())
            }
            GuildScheduledEventTiming.TODAY, GuildScheduledEventTiming.TOMORROW -> {
                val relativeDate = DateUtils.getRelativeTimeSpanString(
                    startTimeMillis,
                    System.currentTimeMillis(),
                    DateUtils.DAY_IN_MILLIS,
                )
                context.getString(com.discord.R.string.guild_event_date_at_time)
                    .replace("{date}", relativeDate.toString())
                    .replace("{time}", DateUtils.formatDateTime(context, startTimeMillis, DATE_FORMAT_FLAGS))
            }
            GuildScheduledEventTiming.LATER -> DateUtils.formatDateTime(context, startTimeMillis, DATE_FORMAT_FLAGS)
        }
    }

    fun endingText(context: Context, start: Long, end: Long, status: GuildScheduledEventStatus): CharSequence? {
        val startDay = java.util.Calendar.getInstance().apply { timeInMillis = start }
        val endDay = java.util.Calendar.getInstance().apply { timeInMillis = end }
        if (startDay.get(java.util.Calendar.YEAR) != endDay.get(java.util.Calendar.YEAR) ||
            startDay.get(java.util.Calendar.DAY_OF_YEAR) != endDay.get(java.util.Calendar.DAY_OF_YEAR)
        ) return null
        return when (timing(start, status)) {
            GuildScheduledEventTiming.TODAY, GuildScheduledEventTiming.SOON, GuildScheduledEventTiming.LIVE,
            GuildScheduledEventTiming.TOMORROW, GuildScheduledEventTiming.LATER ->
                DateUtils.formatDateTime(context, end, DATE_FORMAT_FLAGS)
            else -> null
        }
    }
}
