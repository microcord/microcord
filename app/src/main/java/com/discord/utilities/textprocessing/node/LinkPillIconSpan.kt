package com.discord.utilities.textprocessing.node

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.style.ReplacementSpan
import androidx.annotation.ColorInt

/**
 * A leading icon drawn *on* the mention pill background.
 *
 * `BetterImageSpan` is a `ReplacementSpan`, so the `BackgroundColorSpan` behind it is never
 * painted and the icon floats outside the pill. This span paints the same pill background behind
 * the drawable first, then the drawable, so the icon and the following text sit in one continuous
 * background.
 */
class LinkPillIconSpan(
    private val drawable: Drawable,
    @ColorInt private val backgroundColor: Int,
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        val height = drawable.bounds.height()
        if (fm != null && height > fm.descent - fm.ascent) {
            val extra = height - (fm.descent - fm.ascent)
            val half = extra / 2
            fm.ascent -= half
            fm.top -= half
            fm.descent += extra - half
            fm.bottom += extra - half
        }
        return drawable.bounds.width()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val backgroundPaint = TextPaint(paint).apply {
            color = backgroundColor
        }
        canvas.drawRect(x, top.toFloat(), x + drawable.bounds.width(), bottom.toFloat(), backgroundPaint)

        val save = canvas.save()
        canvas.translate(x, top + (bottom - top - drawable.bounds.height()) / 2f)
        drawable.draw(canvas)
        canvas.restoreToCount(save)
    }
}
