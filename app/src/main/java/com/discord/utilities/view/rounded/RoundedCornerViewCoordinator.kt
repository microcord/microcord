package com.discord.utilities.view.rounded

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.view.View

internal class RoundedCornerViewCoordinator {
    private var topLeftRadius = 0f
    private var topRightRadius = 0f
    private var bottomLeftRadius = 0f
    private var bottomRightRadius = 0f
    private var path = Path()
    private val radii = FloatArray(8)

    fun setRadii(topLeft: Float, topRight: Float, bottomLeft: Float, bottomRight: Float) {
        topLeftRadius = topLeft
        topRightRadius = topRight
        bottomLeftRadius = bottomLeft
        bottomRightRadius = bottomRight
    }

    fun onSizeChanged(width: Int, height: Int) {
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

    fun draw(canvas: Canvas, content: () -> Unit) {
        canvas.save()
        canvas.clipPath(path)
        content()
        canvas.restore()
    }

    fun updateTopLeft(view: View, radius: Float) {
        topLeftRadius = radius
        view.invalidate()
    }

    fun updateTopRight(view: View, radius: Float) {
        topRightRadius = radius
        view.invalidate()
    }

    fun updateBottomLeft(view: View, radius: Float) {
        bottomLeftRadius = radius
        view.invalidate()
    }

    fun updateBottomRight(view: View, radius: Float) {
        bottomRightRadius = radius
        view.invalidate()
    }
}
