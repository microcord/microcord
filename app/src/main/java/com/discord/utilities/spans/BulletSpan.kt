package com.discord.utilities.spans

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.Layout
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import androidx.annotation.ColorInt

/**
 * The bullet drawn beside a markdown list item.
 *
 * A local replacement for the platform's `android.text.style.BulletSpan`, which before API 28 has
 * no way to set the radius and always paints in the paint's current colour.
 *
 * Two draw paths, deliberately not unified. On a hardware-accelerated canvas `drawCircle` is
 * rasterised per call, so a shared [Path] holding a single circle is translated into place instead
 * — and that path is 1.2x the nominal radius, because path rasterisation of a small circle comes
 * out visually smaller than the equivalent `drawCircle`. On a software canvas `drawCircle` is used
 * directly at the true radius.
 */
class BulletSpan : LeadingMarginSpan {

    private val gapWidth: Int
    private val bulletRadius: Int
    private val wantColor: Boolean

    @ColorInt
    private val color: Int
    private val style: Paint.Style
    private val strokeWidth: Float

    /**
     * Bullet painted in the surrounding text's colour.
     *
     * Note the two constructors both accept `(Int, Int)`, so a two-argument call is ambiguous —
     * name the arguments. Neither carries `@JvmOverloads` for the same reason: the generated
     * overloads would collide on the JVM.
     */
    constructor(
        gapWidth: Int = STANDARD_GAP_WIDTH_PX,
        bulletRadius: Int = STANDARD_BULLET_RADIUS_PX,
        strokeWidth: Float = STANDARD_STROKE_WIDTH_PX,
        style: Paint.Style = Paint.Style.FILL,
    ) {
        this.gapWidth = gapWidth
        this.bulletRadius = bulletRadius
        this.wantColor = false
        this.color = 0
        this.style = style
        this.strokeWidth = strokeWidth
    }

    /** Bullet painted in an explicit [color]. */
    constructor(
        gapWidth: Int,
        @ColorInt color: Int,
        bulletRadius: Int = STANDARD_BULLET_RADIUS_PX,
        strokeWidth: Float = STANDARD_STROKE_WIDTH_PX,
        style: Paint.Style = Paint.Style.FILL,
    ) {
        this.gapWidth = gapWidth
        this.bulletRadius = bulletRadius
        this.wantColor = true
        this.color = color
        this.style = style
        this.strokeWidth = strokeWidth
    }

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
        // Only the first line of a wrapped list item gets a bullet.
        if ((text as Spanned).getSpanStart(this) != start) return

        val originalStyle = p.style
        var originalColor = 0
        if (wantColor) {
            originalColor = p.color
            p.color = color
        }
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
            c.drawCircle(
                (x + dir * bulletRadius).toFloat(),
                (top + bottom) / 2f,
                bulletRadius.toFloat(),
                p,
            )
        }

        if (wantColor) p.color = originalColor
        p.style = originalStyle
    }

    companion object {
        const val STANDARD_GAP_WIDTH_PX = 2
        const val STANDARD_BULLET_RADIUS_PX = 4
        const val STANDARD_STROKE_WIDTH_PX = 4.0f

        /** Compensates for path rasterisation of a small circle rendering visually smaller. */
        private const val HARDWARE_RADIUS_SCALE = 1.2f

        /**
         * Shared across every bullet in the process; sized from the first span drawn.
         *
         * A second span with a different [bulletRadius] reuses the first one's path — a reference
         * quirk that is invisible in practice because every list bullet in the app is the standard
         * radius.
         */
        private var bulletPath: Path? = null
    }
}
