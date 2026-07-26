package com.discord.utilities.spans

import android.graphics.Paint
import android.text.Spanned
import android.text.style.LineHeightSpan

/**
 * Adds vertical breathing room above the first line and below the last line of a span, without
 * padding the lines in between.
 *
 * The pristine font metrics are captured on the very first `chooseHeight` call and restored for
 * every interior line. That snapshot matters: `chooseHeight` is handed the *same mutable*
 * `FontMetricsInt` on every line and on every relayout, so without a baseline to restore to, the
 * padding would compound each pass and the block would grow every time the text is measured.
 */
class VerticalPaddingSpan(
    private val paddingTop: Int,
    val paddingBottom: Int,
) : LineHeightSpan {

    private var initialized = false
    private var origTop = -1
    private var origAscent = -1
    private var origBottom = -1
    private var origDescent = -1

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        v: Int,
        fontMetrics: Paint.FontMetricsInt,
    ) {
        val spanned = text as? Spanned ?: return

        if (!initialized) {
            origTop = fontMetrics.top
            origAscent = fontMetrics.ascent
            origBottom = fontMetrics.bottom
            origDescent = fontMetrics.descent
            initialized = true
        }

        if (spanned.getSpanStart(this) == start) {
            fontMetrics.top -= paddingTop
            fontMetrics.ascent -= paddingTop
        } else {
            fontMetrics.top = origTop
            fontMetrics.ascent = origAscent
        }

        if (spanned.getSpanEnd(this) == end) {
            fontMetrics.bottom += paddingBottom
            fontMetrics.descent += paddingBottom
        } else {
            fontMetrics.bottom = origBottom
            fontMetrics.descent = origDescent
        }
    }
}
