package com.discord.utilities.textprocessing.spans

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Spanned
import android.text.style.LineBackgroundSpan
import androidx.annotation.ColorInt
import androidx.annotation.Px

/**
 * Rounded, bordered background drawn behind a whole block (fenced code block).
 *
 * The rectangle is accumulated across the lines of the span and drawn once on the last line so
 * multi line blocks get a single card instead of one box per line.
 */
class BlockBackgroundSpan(
    @ColorInt fillColor: Int,
    @ColorInt borderColor: Int,
    @Px borderWidth: Int,
    @Px cornerRadius: Int,
    @Px val leftMargin: Int,
) : LineBackgroundSpan {

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = borderColor
        strokeWidth = borderWidth.toFloat()
        isAntiAlias = true
    }
    private val rect = RectF()
    private val radius = cornerRadius.toFloat()

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
        if (spanned.getSpanStart(this) == start) {
            rect.left = (left + leftMargin).toFloat()
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
