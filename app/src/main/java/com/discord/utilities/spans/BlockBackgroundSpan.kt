package com.discord.utilities.spans

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Spanned
import android.text.style.LineBackgroundSpan
import androidx.annotation.ColorInt

/**
 * The rounded, outlined box behind a fenced code block.
 *
 * Like [QuoteSpan] the rect spans multiple lines and is drawn exactly once, on the line where the
 * span ends: the left/top edges are captured at the span's first line and the right/bottom at its
 * last. Anything else would round every line individually.
 *
 * [leftMargin] is exposed because the caller must also pair this with a leading-margin span of the
 * same width — this span only paints, it does not reserve room for itself.
 */
class BlockBackgroundSpan(
    @ColorInt fillColor: Int,
    @ColorInt strokeColor: Int,
    strokeWidth: Int,
    strokeRadius: Int,
    val leftMargin: Int,
) : LineBackgroundSpan {

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = fillColor
    }

    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = strokeColor
        this.strokeWidth = strokeWidth.toFloat()
        isAntiAlias = true
    }

    private val rect = RectF()
    private val radius = strokeRadius.toFloat()

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
        lnum: Int,
    ) {
        val spanned = text as? Spanned ?: return

        if (spanned.getSpanStart(this) == start) {
            rect.left = left.toFloat() + leftMargin
            rect.top = top.toFloat()
        }

        if (spanned.getSpanEnd(this) == end) {
            rect.right = right.toFloat()
            rect.bottom = bottom.toFloat()
            draw(canvas)
        }
    }

    fun draw(canvas: Canvas) {
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
    }
}
