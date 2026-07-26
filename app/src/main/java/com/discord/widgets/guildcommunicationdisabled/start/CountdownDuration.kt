package com.discord.widgets.guildcommunicationdisabled.start

import android.content.Context
import com.discord.R

/**
 * `"2d 3h 04m 07s"` — the compact countdown the timeout surfaces render.
 *
 * This is a port of the reference's `com.discord.utilities.duration.DurationUtilsKt`
 * `humanizeCountdownDuration`. That package does not exist in this project yet and creating it was
 * outside this agent's file ownership, so the one function that is actually needed lives here.
 * **Move it to `utilities/duration/` when that file lands** — the sibling `humanizeDuration` and
 * `humanizeDurationRounded` belong there too and are not reconstructed here.
 *
 * Reference quirks reproduced deliberately:
 *  - the seconds component is clamped to a minimum of 1, so the countdown never reads `0s`;
 *  - the leading unit is dropped when it is zero, but the minute and second components always show.
 */
fun humanizeCountdownDuration(context: Context, durationMillis: Long): CharSequence {
    val days = (durationMillis / MILLIS_PER_DAY).toInt()
    val hours = (durationMillis / MILLIS_PER_HOUR).toInt() % 24
    val minutes = (durationMillis / MILLIS_PER_MINUTE).toInt() % 60
    val seconds = ((durationMillis / MILLIS_PER_SECOND).toInt() % 60).coerceAtLeast(1)

    val minutesAndSeconds = buildString {
        append(minutes).append(context.getString(R.string.countdown_units_minutes))
        append(' ')
        append(seconds).append(context.getString(R.string.countdown_units_seconds))
    }

    return when {
        days > 0 -> buildString {
            append(days).append(context.getString(R.string.countdown_units_days))
            append(' ')
            append(hours).append(context.getString(R.string.countdown_units_hours))
            append(' ')
            append(minutesAndSeconds)
        }

        hours > 0 -> buildString {
            append(hours).append(context.getString(R.string.countdown_units_hours))
            append(' ')
            append(minutesAndSeconds)
        }

        else -> minutesAndSeconds
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 3_600_000L
private const val MILLIS_PER_DAY = 86_400_000L
