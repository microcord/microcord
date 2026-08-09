package com.discord.widgets.chat.input

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan

class ComposerMentionSpan(
    private val display: String,
    private val textColor: Int,
    private val backgroundColor: Int,
    density: Float,
) : ReplacementSpan() {
    private val padding = 2f * density
    private val radius = 3f * density

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int = (paint.measureText(display) + padding * 2).toInt()

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
        val width = paint.measureText(display) + padding * 2
        val oldColor = paint.color
        paint.color = backgroundColor
        canvas.drawRoundRect(x, y + paint.ascent(), x + width, y + paint.descent(), radius, radius, paint)
        paint.color = textColor
        canvas.drawText(display, x + padding, y.toFloat(), paint)
        paint.color = oldColor
    }
}
