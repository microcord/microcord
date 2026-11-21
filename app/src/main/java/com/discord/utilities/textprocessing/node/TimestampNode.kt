package com.discord.utilities.textprocessing.node

import android.icu.text.RelativeDateTimeFormatter
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import androidx.annotation.RequiresApi
import com.discord.R
import com.discord.simpleast.core.node.Node
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.textprocessing.spans.TextBackgroundSpan
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * `<t:seconds>` and `<t:seconds:style>` timestamps, in RN's styles (`TimestampUtils`).
 *
 * The suffix-less form, and any style this does not know, is `f`. Tapping shows the full date so a
 * relative timestamp can be read exactly.
 */
class TimestampNode<T : TimestampNode.RenderContext>(
    timestampString: String,
    formatString: String?,
    now: Long = System.currentTimeMillis(),
) : Node<T>() {

    interface RenderContext : BasicRenderContext {
        fun onTimestampClicked(full: CharSequence) = Unit
    }

    /** Absolute rendering, always shown when the timestamp is tapped. */
    val full: CharSequence

    /** Rendering selected by the style suffix, shown inline. */
    val formatted: CharSequence

    init {
        val date = Date(timestampString.toLong() * MILLIS_PER_SECOND)
        full = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(date)
        formatted = when (formatString) {
            SHORT_TIME -> DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
            LONG_TIME -> DateFormat.getTimeInstance(DateFormat.MEDIUM).format(date)
            SHORT_DATE -> DateFormat.getDateInstance(DateFormat.SHORT).format(date)
            LONG_DATE -> DateFormat.getDateInstance(DateFormat.LONG).format(date)
            LONG_DATE_TIME -> full
            SHORT_DATE_SHORT_TIME -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(date)
            SHORT_DATE_LONG_TIME -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(date)
            RELATIVE -> relative(date.time, now)
            else -> DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(date)
        }
    }

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val styles = listOf(
            ClickableSpan(null, false) { renderContext.onTimestampClicked(full) },
            TextBackgroundSpan(ColorCompat.getThemedColor(renderContext.context, R.attr.colorBackgroundModifierAccent)),
        )
        val start = builder.length
        builder.append(formatted)
        styles.forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    internal companion object {
        private const val MILLIS_PER_SECOND = 1000L

        private const val SHORT_TIME = "t"
        private const val LONG_TIME = "T"
        private const val SHORT_DATE = "d"
        private const val LONG_DATE = "D"
        private const val LONG_DATE_TIME = "F"
        private const val SHORT_DATE_SHORT_TIME = "s"
        private const val SHORT_DATE_LONG_TIME = "S"
        private const val RELATIVE = "R"

        /** A relative amount: how many of which unit, as moment's `fromNow` picks them. */
        internal enum class Unit { SECONDS, MINUTES, HOURS, DAYS, MONTHS, YEARS }

        /**
         * moment's `relativeTime` with the thresholds RN sets for timestamps: seconds count up to a
         * minute, minutes to an hour, then hours to 22, days to 26, months to 11, then years.
         * Each unit is the whole difference rounded, so 45 minutes is "45 minutes", 90 is "2 hours".
         */
        internal fun relativeAmount(millis: Long): Pair<Long, Unit> {
            val seconds = (millis / 1000.0).roundToLong()
            val minutes = (millis / 60_000.0).roundToLong()
            val hours = (millis / 3_600_000.0).roundToLong()
            val days = (millis / 86_400_000.0).roundToLong()
            // moment's days-to-months: 4800 months in 146097 days.
            val months = (millis / 86_400_000.0 * 4800 / 146097).roundToLong()
            val years = (millis / 86_400_000.0 / 365.2425).roundToLong()
            return when {
                seconds < 60 -> seconds to Unit.SECONDS
                minutes < 60 -> maxOf(minutes, 1) to Unit.MINUTES
                hours < 22 -> maxOf(hours, 1) to Unit.HOURS
                days < 26 -> maxOf(days, 1) to Unit.DAYS
                months < 11 -> maxOf(months, 1) to Unit.MONTHS
                else -> maxOf(years, 1) to Unit.YEARS
            }
        }

        private fun relative(time: Long, now: Long): CharSequence {
            val diff = time - now
            val (amount, unit) = relativeAmount(abs(diff))
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                icuRelative(amount, unit, future = diff > 0)
            } else {
                DateUtils.getRelativeTimeSpanString(time, now, DateUtils.SECOND_IN_MILLIS)
            }
        }

        /** In the reader's language, which hand-written English would not be. */
        @RequiresApi(Build.VERSION_CODES.N)
        private fun icuRelative(amount: Long, unit: Unit, future: Boolean): String =
            RelativeDateTimeFormatter.getInstance().format(
                amount.toDouble(),
                if (future) RelativeDateTimeFormatter.Direction.NEXT else RelativeDateTimeFormatter.Direction.LAST,
                when (unit) {
                    Unit.SECONDS -> RelativeDateTimeFormatter.RelativeUnit.SECONDS
                    Unit.MINUTES -> RelativeDateTimeFormatter.RelativeUnit.MINUTES
                    Unit.HOURS -> RelativeDateTimeFormatter.RelativeUnit.HOURS
                    Unit.DAYS -> RelativeDateTimeFormatter.RelativeUnit.DAYS
                    Unit.MONTHS -> RelativeDateTimeFormatter.RelativeUnit.MONTHS
                    Unit.YEARS -> RelativeDateTimeFormatter.RelativeUnit.YEARS
                },
            )
    }
}
