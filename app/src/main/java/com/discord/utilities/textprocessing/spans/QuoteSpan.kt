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
        if (spanned.getSpanStart(this) == start) {
            rect.left = x.toFloat()
            rect.right = (x + dir * stripeWidth).toFloat()
            rect.top = top.toFloat()
        }
        val spanEnd = spanned.getSpanEnd(this)
        val lastLine = spanEnd == end || (spanEnd - 1 == end && text[end] == '\n')
        if (lastLine) {
            rect.bottom = bottom.toFloat()
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
