package com.discord.views.guildtag

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import com.discord.api.guild.profile.GuildTagBadge
import kotlin.math.pow

/**
 * Draws a guild tag badge, recoloured to the guild's chosen primary/secondary tints.
 *
 * A plain `VectorDrawable` cannot do this: the art is a stack of paths whose fills have to move
 * independently, and Android offers no per-path recolouring once a vector is inflated. So the paths
 * are parsed once and painted here instead.
 */
class GuildTagBadgeDrawable(
    private val badge: GuildTagBadge,
    primaryTint: Int? = null,
    secondaryTint: Int? = null,
) : Drawable() {

    private val art = requireNotNull(GUILD_TAG_BADGE_ART[badge]) { "No art for $badge" }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scratch = Path()
    private val matrix = Matrix()

    private val primary = ramp(primaryTint, art.primaryBase, art.primaryLuminances, art.primaryWeights)
    private val secondary =
        ramp(secondaryTint, art.secondaryBase, art.secondaryLuminances, art.secondaryWeights)

    private val parsed: List<Pair<Path, Int>> = art.paths.map { (data, slot) ->
        PathParser.createPathFromPathData(data) to when (slot) {
            is Slot.Fixed -> slot.color
            is Slot.Primary -> primary.getOrElse(slot.index) { Color.BLACK }
            is Slot.Secondary -> secondary.getOrElse(slot.index) { Color.BLACK }
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        matrix.setScale(b.width() / BADGE_VIEWPORT, b.height() / BADGE_VIEWPORT)
        matrix.postTranslate(b.left.toFloat(), b.top.toFloat())
        parsed.forEach { (path, color) ->
            path.transform(matrix, scratch)
            paint.color = color
            canvas.drawPath(scratch, paint)
        }
    }

    override fun getIntrinsicWidth() = BADGE_VIEWPORT.toInt()

    override fun getIntrinsicHeight() = BADGE_VIEWPORT.toInt()

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        /**
         * Re-lights [tint] to each of the ramp's target luminances, so a badge keeps its internal
         * light-to-dark structure whatever colour it is tinted. Each target is a weighted blend of
         * the slot's own luminance and the tint's, matching the reference's `chroma-js` maths.
         */
        fun ramp(tint: Int?, base: IntArray, luminances: DoubleArray, weights: IntArray): IntArray {
            if (tint == null || luminances.isEmpty()) return base
            val tintLuminance = relativeLuminance(tint)
            return IntArray(luminances.size) { index ->
                val baseWeight = weights[index * 2]
                val tintWeight = weights[index * 2 + 1]
                val target =
                    (luminances[index] * baseWeight + tintLuminance * tintWeight) / (baseWeight + tintWeight)
                withLuminance(tint, target)
            }
        }

        fun relativeLuminance(color: Int): Double {
            fun channel(value: Int): Double {
                val v = value / 255.0
                return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(Color.red(color)) +
                0.7152 * channel(Color.green(color)) +
                0.0722 * channel(Color.blue(color))
        }

        /** Binary-searches a blend towards white or black until the luminance lands on [target]. */
        fun withLuminance(color: Int, target: Double): Int {
            val current = relativeLuminance(color)
            if (kotlin.math.abs(current - target) < EPSILON) return color
            // Blending towards white raises luminance with the ratio and towards black lowers it,
            // so which half to keep flips with the direction.
            val lighten = target > current
            val towards = if (lighten) Color.WHITE else Color.BLACK
            var low = 0.0
            var high = 1.0
            var result = color
            repeat(SEARCH_STEPS) {
                val mid = (low + high) / 2
                result = blend(color, towards, mid)
                val tooDark = relativeLuminance(result) < target
                if (tooDark == lighten) low = mid else high = mid
            }
            return result
        }

        fun blend(from: Int, to: Int, ratio: Double): Int = Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * ratio).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * ratio).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio).toInt(),
        )

        const val EPSILON = 0.0005
        const val SEARCH_STEPS = 20
    }
}
