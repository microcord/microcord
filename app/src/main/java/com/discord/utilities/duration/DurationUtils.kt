@file:JvmName("DurationUtils")

package com.discord.utilities.duration

import android.content.Context
import com.discord.R

/**
 * A countdown rendered as `1d 4h 23m 6s`, dropping leading units that are zero.
 *
 * Two details are load-bearing and are reproduced from the reference:
 *
 * * seconds are clamped to a minimum of **1**, so a countdown never reads `0s` while it is still
 *   running — it goes `1s`, then disappears; and
 * * minutes are computed from the whole duration and then taken mod 60, while hours are taken mod
 *   24 — so the days component absorbs everything above 24 hours rather than the hours component
 *   growing without bound.
 *
 * Reference: `com.discord.utilities.duration.DurationUtilsKt.humanizeCountdownDuration`.
 */
fun humanizeCountdownDuration(context: Context, durationMillis: Long): CharSequence {
    val millis = durationMillis.toFloat()
    val days = (millis / MILLIS_PER_DAY).toInt()
    val hours = (millis / MILLIS_PER_HOUR).toInt() % 24
    val totalMinutes = (millis / MILLIS_PER_MINUTE).toInt()
    val seconds = ((millis / MILLIS_PER_SECOND).toInt() % 60).coerceAtLeast(1)

    val minutesAndSeconds = buildString {
        append(totalMinutes % 60)
        append(context.getString(R.string.countdown_units_minutes))
        append(' ')
        append(seconds)
        append(context.getString(R.string.countdown_units_seconds))
    }

    return when {
        days > 0 -> buildString {
            append(days)
            append(context.getString(R.string.countdown_units_days))
            append(' ')
            append(hours)
            append(context.getString(R.string.countdown_units_hours))
            append(' ')
            append(minutesAndSeconds)
        }
        hours > 0 -> buildString {
            append(hours)
            append(context.getString(R.string.countdown_units_hours))
            append(' ')
            append(minutesAndSeconds)
        }
        else -> minutesAndSeconds
    }
}

private const val MILLIS_PER_SECOND = 1_000f
private const val MILLIS_PER_MINUTE = 60_000f
private const val MILLIS_PER_HOUR = 3_600_000f
private const val MILLIS_PER_DAY = 86_400_000f
