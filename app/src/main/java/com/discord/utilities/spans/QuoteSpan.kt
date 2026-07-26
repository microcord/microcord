package com.discord.utilities.spans

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import androidx.annotation.ColorInt

/**
 * The rounded vertical stripe drawn to the left of a `> ` block quote.
 *
 * A single rounded rect is accumulated across the span's lines rather than one per line: the top
 * edge is captured on the line where the span starts, the bottom on the line where it ends, and the
 * rect is only drawn once, on that final line. Drawing per-line would round every line's corners
 * and leave visible seams between them.
 *
 * The corner radius is the stripe width, so the stripe is a capsule whatever the density.
 */
class QuoteSpan(
    @ColorInt val stripeColor: Int,
    val stripeWidth: Int,
    val gapWidth: Int,
) : LeadingMarginSpan {

    private val rect = RectF()
    private val radius = stripeWidth.toFloat()

    override fun getLeadingMargin(first: Boolean): Int = stripeWidth + gapWidth

    override fun drawLeadingMargin(
        c: Canvas,
        p: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout,
    ) {
        val spanned = text as? Spanned ?: return

        if (spanned.getSpanStart(this) == start) {
            rect.left = x.toFloat()
            rect.right = (x + dir * stripeWidth).toFloat()
            rect.top = top.toFloat()
        }

        // The quote's own trailing newline is inside the span, so the last rendered line ends one
        // character short of the span end. Both cases close the stripe.
        val spanEnd = spanned.getSpanEnd(this)
        if (spanEnd == end || (spanEnd - 1 == end && text[end] == '\n')) {
            rect.bottom = bottom.toFloat()
            draw(c, p)
        }
    }

    private fun draw(c: Canvas, p: Paint) {
        val originalStyle = p.style
        val originalColor = p.color
        p.style = Paint.Style.FILL
        p.color = stripeColor
        c.drawRoundRect(rect, radius, radius, p)
        p.style = originalStyle
        p.color = originalColor
    }
}
