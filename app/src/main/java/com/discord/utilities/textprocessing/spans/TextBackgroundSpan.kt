package com.discord.utilities.textprocessing.spans

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.MetricAffectingSpan
import androidx.annotation.ColorInt

/**
 * The pill behind a run of inline text — a mention, inline code, a timestamp — sized to that text.
 *
 * `BackgroundColorSpan` fills from the top of the line to its bottom, so any line taller than the
 * run (a header's spacing, a big emoji beside it) stretched the pill to the line. This draws each
 * line's share of the run itself: placed after the line's indentation (quotes, lists), from either
 * edge as the paragraph's direction says, as tall as the run's own font, with Discord's corners.
 */
class TextBackgroundSpan(@ColorInt color: Int) : LineBackgroundSpan {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val measure = TextPaint()
    private val rect = RectF()

    override fun drawBackground(
        canvas: Canvas,
        paint: Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        lineNumber: Int,
    ) {
        val spanned = text as? Spanned ?: return
        val from = maxOf(spanned.getSpanStart(this), start)
        var to = minOf(spanned.getSpanEnd(this), end)
        while (to > from && text[to - 1] == '\n') to--
        if (to <= from) return

        measure.set(paint as? TextPaint ?: TextPaint(paint))
        val before = runWidth(spanned, start, from)
        val width = runWidth(spanned, from, to)

        val paragraphStart = text.lastIndexOf('\n', start - 1) + 1
        val paragraphEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val firstLine = start == paragraphStart
        val indent = spanned.getSpans(start, start + 1, LeadingMarginSpan::class.java)
            .sumOf { it.getLeadingMargin(firstLine) }
        val rtl = TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(text, paragraphStart, paragraphEnd - paragraphStart)
        val x = if (rtl) right - indent - before - width else left + indent + before

        // The run's own size, with whatever scales it (a header, inline code's smaller font).
        spanned.getSpans(from, from + 1, MetricAffectingSpan::class.java).forEach { it.updateMeasureState(measure) }
        val metrics = measure.fontMetrics
        rect.set(x, baseline + metrics.ascent, x + width, baseline + metrics.descent)
        val radius = measure.textSize * CORNER_RADIUS
        canvas.drawRoundRect(rect, radius, radius, fill)
    }

    /**
     * How wide [from]..[to] is drawn. `Layout.getDesiredWidth` measures the run as a paragraph of
     * its own and adds that paragraph's indentation, which the line has already been given.
     */
    private fun runWidth(text: Spanned, from: Int, to: Int): Float {
        if (to <= from) return 0f
        val indent = text.getSpans(from, to, LeadingMarginSpan::class.java).sumOf { it.getLeadingMargin(false) }
        return Layout.getDesiredWidth(text, from, to, measure) - indent
    }

    private companion object {
        /** Discord's 3px mention corner on its 16px text. */
        const val CORNER_RADIUS = 3f / 16f
    }
}
