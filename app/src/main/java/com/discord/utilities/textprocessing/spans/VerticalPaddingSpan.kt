package com.discord.utilities.textprocessing.spans

import android.graphics.Paint
import android.text.Spanned
import android.text.style.LineHeightSpan
import androidx.annotation.Px

/**
 * Adds padding above the first line and below the last line covered by the span without
 * changing the leading of the lines in between.
 */
class VerticalPaddingSpan(
    @Px private val paddingTop: Int,
    @Px val paddingBottom: Int,
) : LineHeightSpan {

    private var initialized = false
    private var originalTop = -1
    private var originalAscent = -1
    private var originalBottom = -1
    private var originalDescent = -1

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fontMetrics: Paint.FontMetricsInt,
    ) {
        val spanned = text as? Spanned ?: return
        if (!initialized) {
            originalTop = fontMetrics.top
            originalAscent = fontMetrics.ascent
            originalBottom = fontMetrics.bottom
            originalDescent = fontMetrics.descent
            initialized = true
        }

        if (spanned.getSpanStart(this) == start) {
            fontMetrics.top -= paddingTop
            fontMetrics.ascent -= paddingTop
        } else {
            fontMetrics.top = originalTop
            fontMetrics.ascent = originalAscent
        }

        if (spanned.getSpanEnd(this) == end) {
            fontMetrics.bottom += paddingBottom
            fontMetrics.descent += paddingBottom
        } else {
            fontMetrics.bottom = originalBottom
            fontMetrics.descent = originalDescent
        }
    }
}
