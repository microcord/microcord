package com.discord.utilities.textprocessing.spans

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import androidx.annotation.Px

/**
 * Gives the character it covers an exact width and draws nothing.
 *
 * An ordered list writes its marker as text, so the space after the number would otherwise be
 * whatever the font makes it, and would not line up with the gap [BulletSpan] reserves.
 */
class FixedWidthSpan(@Px private val width: Int) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fontMetrics: Paint.FontMetricsInt?,
    ): Int = width

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
    ) = Unit
}
