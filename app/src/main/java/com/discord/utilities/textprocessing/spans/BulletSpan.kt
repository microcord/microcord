package com.discord.utilities.textprocessing.spans

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.Layout
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import androidx.annotation.ColorInt
import androidx.annotation.Px

/**
 * Markdown list bullet.
 *
 * Behaves like the platform bullet span but adds an explicit colour, a configurable radius and a
 * paint style so nested list levels can be drawn as outlines instead of filled dots.
 */
class BulletSpan(
    @Px private val gapWidth: Int = STANDARD_GAP_WIDTH_PX,
    @ColorInt private val color: Int? = null,
    @Px private val bulletRadius: Int = STANDARD_BULLET_RADIUS_PX,
    private val strokeWidth: Float = STANDARD_STROKE_WIDTH_PX,
    private val style: Paint.Style = Paint.Style.FILL,
) : LeadingMarginSpan {

    /** Cached because the circle only depends on [bulletRadius], which is fixed per span. */
    private var bulletPath: Path? = null

    override fun getLeadingMargin(first: Boolean): Int = bulletRadius * 2 + gapWidth

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
        l: Layout,
    ) {
        val spanned = text as? Spanned ?: return
        if (spanned.getSpanStart(this) != start) return

        val previousStyle = p.style
        val previousColor = p.color
        color?.let { p.color = it }
        p.style = style
        if (style == Paint.Style.STROKE || style == Paint.Style.FILL_AND_STROKE) {
            p.strokeWidth = strokeWidth
        }

        if (c.isHardwareAccelerated) {
            val path = bulletPath ?: Path().also {
                it.addCircle(0f, 0f, bulletRadius * HARDWARE_RADIUS_SCALE, Path.Direction.CW)
                bulletPath = it
            }
            c.save()
            c.translate(x + dir * HARDWARE_RADIUS_SCALE * bulletRadius, (top + bottom) / 2f)
            c.drawPath(path, p)
            c.restore()
        } else {
            c.drawCircle((x + dir * bulletRadius).toFloat(), (top + bottom) / 2f, bulletRadius.toFloat(), p)
        }

        color?.let { p.color = previousColor }
        p.style = previousStyle
    }

    companion object {
        const val STANDARD_BULLET_RADIUS_PX = 4
        const val STANDARD_GAP_WIDTH_PX = 2
        const val STANDARD_STROKE_WIDTH_PX = 4.0f

        private const val HARDWARE_RADIUS_SCALE = 1.2f
    }
}
