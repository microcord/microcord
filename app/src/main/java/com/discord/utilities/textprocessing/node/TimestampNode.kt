package com.discord.utilities.textprocessing.node

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.BackgroundColorSpan
import com.discord.R
import com.discord.simpleast.core.node.Node
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.spans.ClickableSpan
import java.text.DateFormat
import java.util.Date

/**
 * `<t:seconds>` and `<t:seconds:style>` timestamps.
 *
 * The style suffix selects one of Discord's documented presentations; anything unrecognised (and
 * the suffix-less form) falls back to the long-date/short-time form. Tapping shows the absolute
 * value so the reader can resolve a relative timestamp.
 */
class TimestampNode<T : TimestampNode.RenderContext>(
    timestampString: String,
    formatString: String?,
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
            RELATIVE -> DateUtils.getRelativeTimeSpanString(date.time)
            SHORT_TIME -> DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
            LONG_TIME -> DateFormat.getTimeInstance(DateFormat.MEDIUM).format(date)
            SHORT_DATE -> DateFormat.getDateInstance(DateFormat.SHORT).format(date)
            LONG_DATE -> DateFormat.getDateInstance(DateFormat.LONG).format(date)
            SHORT_DATE_TIME -> DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(date)
            LONG_DATE_TIME -> DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(date)
            else -> DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(date)
        }
    }

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val styles = listOf(
            ClickableSpan(null, false) { renderContext.onTimestampClicked(full) },
            BackgroundColorSpan(ColorCompat.getThemedColor(renderContext.context, R.attr.colorBackgroundModifierAccent)),
        )
        val start = builder.length
        builder.append(formatted)
        styles.forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L

        const val SHORT_TIME = "t"
        const val LONG_TIME = "T"
        const val SHORT_DATE = "d"
        const val LONG_DATE = "D"
        const val SHORT_DATE_TIME = "f"
        const val LONG_DATE_TIME = "F"
        const val RELATIVE = "R"
    }
}
