package com.discord.utilities.view.rounded

import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider

/**
 * Rounds a layout's corners.
 *
 * Whenever the corners are one shared radius on all four sides, or on just the top or bottom
 * pair, the view is clipped to a rounded-rect outline, which the render thread does almost for
 * free: the home panels round only their top corners and are dragged across the screen every
 * swipe, where the full-screen `clipPath` this otherwise falls back to was the slowest draw
 * command. A single side is rounded by letting the outline run one radius past the opposite edge.
 */
internal class RoundedCornerViewCoordinator {
    private var topLeftRadius = 0f
    private var topRightRadius = 0f
    private var bottomLeftRadius = 0f
    private var bottomRightRadius = 0f
    private var path: Path? = null
    private val radii = FloatArray(8)

    fun setRadii(topLeft: Float, topRight: Float, bottomLeft: Float, bottomRight: Float) {
        topLeftRadius = topLeft
        topRightRadius = topRight
        bottomLeftRadius = bottomLeft
        bottomRightRadius = bottomRight
    }

    fun onSizeChanged(view: View, width: Int, height: Int) = rebuild(view, width, height)

    fun draw(canvas: Canvas, content: () -> Unit) {
        val clip = path ?: return content()
        canvas.save()
        canvas.clipPath(clip)
        content()
        canvas.restore()
    }

    fun updateTopLeft(view: View, radius: Float) {
        topLeftRadius = radius
        rebuild(view, view.width, view.height)
    }

    fun updateTopRight(view: View, radius: Float) {
        topRightRadius = radius
        rebuild(view, view.width, view.height)
    }

    fun updateBottomLeft(view: View, radius: Float) {
        bottomLeftRadius = radius
        rebuild(view, view.width, view.height)
    }

    fun updateBottomRight(view: View, radius: Float) {
        bottomRightRadius = radius
        rebuild(view, view.width, view.height)
    }

    private fun rebuild(view: View, width: Int, height: Int) {
        path = null
        view.clipToOutline = false
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.invalidate()

        val top = topLeftRadius
        val bottom = bottomLeftRadius
        val outlineBounds = if (topRightRadius != top || bottomRightRadius != bottom) null else when {
            top == 0f && bottom == 0f -> return
            top == bottom -> intArrayOf(0, 0, width, height)
            bottom == 0f -> intArrayOf(0, 0, width, height + top.toInt() + 1)
            top == 0f -> intArrayOf(0, -bottom.toInt() - 1, width, height)
            else -> null
        }
        if (outlineBounds != null) {
            val radius = maxOf(top, bottom)
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(
                    outlineBounds[0], outlineBounds[1], outlineBounds[2], outlineBounds[3], radius,
                )
            }
            view.clipToOutline = true
            return
        }
        radii[0] = topLeftRadius
        radii[1] = topLeftRadius
        radii[2] = topRightRadius
        radii[3] = topRightRadius
        radii[4] = bottomRightRadius
        radii[5] = bottomRightRadius
        radii[6] = bottomLeftRadius
        radii[7] = bottomLeftRadius
        path = Path().apply {
            addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radii, Path.Direction.CW)
            close()
        }
    }
}
