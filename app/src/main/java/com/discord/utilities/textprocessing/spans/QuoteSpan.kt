package com.discord.utilities.textprocessing.spans

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import androidx.annotation.ColorInt
import androidx.annotation.Px

/**
 * Rounded vertical stripe drawn to the left of a block quote.
 *
 * Unlike the platform [android.text.style.QuoteSpan] the stripe is drawn once for the whole
 * span instead of per line, so consecutive quoted lines share one continuous bar.
 */
class QuoteSpan(
    @ColorInt private val stripeColor: Int,
    @Px private val stripeWidth: Int,
    @Px private val gapWidth: Int,
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
        val spanStart = spanned.getSpanStart(this)
        val spanEnd = spanned.getSpanEnd(this)
        if (spanStart < 0 || spanEnd <= spanStart) return
        // One stripe down the whole quote, drawn when its last line is. Which line that is comes
        // from the layout: the line range handed in here may or may not include the quote's own
        // closing newline, so matching the span's end against it missed the stripe entirely.
        val lastLine = layout.getLineForOffset(maxOf(spanStart, spanEnd - 1))
        if (layout.getLineForOffset(start) == lastLine) {
            val edge = x + dir * stripeWidth
            rect.set(
                minOf(x, edge).toFloat(),
                layout.getLineTop(layout.getLineForOffset(spanStart)).toFloat(),
                maxOf(x, edge).toFloat(),
                bottom.toFloat(),
            )
            drawStripe(c, p)
        }
    }

    private fun drawStripe(canvas: Canvas, paint: Paint) {
        val previousStyle = paint.style
        val previousColor = paint.color
        paint.style = Paint.Style.FILL
        paint.color = stripeColor
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = previousStyle
        paint.color = previousColor
    }
}
