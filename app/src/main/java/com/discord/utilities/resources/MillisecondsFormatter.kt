package com.discord.utilities.resources

import android.content.Context
import kotlin.math.roundToInt

/**
 * Renders a millisecond duration in its largest whole unit ("2 hours", "3 weeks").
 *
 * The unit is the largest one that fits, floored at minutes — anything under a minute still reads
 * as some number of minutes, which is what the slow-mode and timeout pickers want. The quantity is
 * **rounded**, not truncated, so 90 minutes reads "2 hours".
 *
 * Reference: `com.discord.utilities.resources.MillisecondsFormatter`.
 */
object MillisecondsFormatter {

    private const val MS_IN_MINUTE = 60_000L
    private const val MS_IN_HOUR = 3_600_000L
    private const val MS_IN_DAY = 86_400_000L
    private const val MS_IN_WEEK = 604_800_000L
    private const val MS_IN_MONTH = 2_592_000_000L

    fun Context.formatDurationInMillis(millis: Long): CharSequence {
        val (unit, unitMillis) = when {
            millis >= MS_IN_MONTH -> DurationUnit.MONTHS to MS_IN_MONTH
            millis >= MS_IN_WEEK -> DurationUnit.WEEKS to MS_IN_WEEK
            millis >= MS_IN_DAY -> DurationUnit.DAYS to MS_IN_DAY
            millis >= MS_IN_HOUR -> DurationUnit.HOURS to MS_IN_HOUR
            else -> DurationUnit.MINUTES to MS_IN_MINUTE
        }
        return formatDuration(unit, (millis.toDouble() / unitMillis).roundToInt())
    }
}
