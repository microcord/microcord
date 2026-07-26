package com.discord.widgets.guildcommunicationdisabled.start

import android.content.Context
import com.discord.R
import com.discord.i18n.i18nPlural
import com.discord.utilities.time.TimeUtils
import java.util.Calendar

/**
 * Turns a [TimeDurationDisabledCommunication] into the two things the timeout flow needs: a label
 * for the radio row and the ISO-8601 instant the API expects in `communication_disabled_until`.
 */
object GuildCommunicationDisabledDateUtils {

    /**
     * "60 Seconds", "1 Hour", … Localised through the `duration_*_capitalize_*` plurals.
     *
     * [context] is nullable because the caller is a fragment that may already be detached; the
     * reference falls back to the English literal in exactly that case rather than throwing.
     */
    fun getFriendlyDurationString(
        context: Context?,
        durationMs: TimeDurationDisabledCommunication,
    ): CharSequence = when (durationMs) {
        TimeDurationDisabledCommunication.SECONDS_60 ->
            context?.i18nPlural(R.plurals.duration_seconds_capitalize_seconds, 60, "seconds" to 60)
                ?: "60 Seconds"

        TimeDurationDisabledCommunication.MINUTES_5 ->
            context?.i18nPlural(R.plurals.duration_minutes_capitalize_minutes, 5, "minutes" to 5)
                ?: "5 Minutes"

        TimeDurationDisabledCommunication.MINUTES_10 ->
            context?.i18nPlural(R.plurals.duration_minutes_capitalize_minutes, 10, "minutes" to 10)
                ?: "10 Minutes"

        TimeDurationDisabledCommunication.HOUR_1 ->
            context?.i18nPlural(R.plurals.duration_hours_capitalize_hours, 1)
                ?: "1 Hour"

        TimeDurationDisabledCommunication.DAY_1 ->
            context?.i18nPlural(R.plurals.duration_days_capitalize_days, 1)
                ?: "1 Day"

        TimeDurationDisabledCommunication.WEEK_1 ->
            context?.i18nPlural(R.plurals.duration_weeks_capitalize_weeks, 1)
                ?: "1 Week"
    }

    /**
     * `now + [durationMs]`, as the `yyyy-MM-dd'T'HH:mm:ss.000Z` string the API wants plus the raw
     * epoch millis the caller uses for its confirmation copy.
     *
     * The reference formats without fractional seconds and appends a literal `.000Z`; reproduced so
     * the payload is byte-identical.
     */
    fun getFutureTimestamp(durationMs: Int): Pair<String, Long> {
        val date = Calendar.getInstance()
        date.add(Calendar.MILLISECOND, durationMs)
        val timeInMillis = date.timeInMillis
        val formatted = TimeUtils.toUTCDateTime(timeInMillis, "yyyy-MM-dd'T'HH:mm:ss")
        return "$formatted.000Z" to timeInMillis
    }
}
