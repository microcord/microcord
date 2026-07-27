package com.discord.widgets.chat.list

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.discord.R
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimen.dpToPixels

class ViewReplySpline(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f
        color = ColorCompat.getThemedColor(context, R.attr.colorBackgroundAccent)
    }
    private val path = Path()
    private val pathRect = RectF(0f, 0f, 1f, 1f)
    private var pathRectLocal = RectF()
    private var arcRectLocal = RectF()
    private val transformMatrix = Matrix()
    private var orientation = 0
    private var capStyle = 0
    private var spineStartPadding = 0
    private var spineEndPadding = 0

    init {
        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.ViewReplySpline, 0, 0).run {
                val start = getDimension(R.styleable.ViewReplySpline_start_padding, 0f)
                val end = getDimension(R.styleable.ViewReplySpline_end_padding, 0f)
                orientation = getInteger(R.styleable.ViewReplySpline_orientation, 0)
                capStyle = getInteger(R.styleable.ViewReplySpline_cap_style, 0)
                if (start > 0f) spineStartPadding = dpToPixels(start)
                if (end > 0f) spineEndPadding = dpToPixels(end)
                recycle()
            }
        }
    }

    fun createPath() {
        path.reset()
        when (orientation) {
            0 -> {
                path.moveTo(pathRectLocal.left, pathRectLocal.bottom + getLongPathOffset())
                path.arcTo(arcRectLocal, 180f, 90f, false)
                path.lineTo(pathRectLocal.right, pathRectLocal.top)
            }
            1 -> {
                path.moveTo(pathRectLocal.left, pathRectLocal.top - getLongPathOffset())
                path.arcTo(arcRectLocal, 180f, -90f, false)
                path.lineTo(pathRectLocal.right, pathRectLocal.bottom)
            }
        }
    }

    fun getLongPathOffset(): Int = when (capStyle) {
        0 -> 0
        1 -> dpToPixels(1f)
        else -> throw IllegalStateException("Unknown cap style")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw && h == oldh) return
        transformMatrix.reset()
        transformMatrix.preTranslate(3f, 3f)
        transformMatrix.preScale((w - 6f) - spineEndPadding, (h - 6f) - spineStartPadding)
        transformMatrix.mapRect(pathRectLocal, pathRect)
        val arcDiameter = pathRectLocal.width() * 0.25f * 2f
        arcRectLocal = when (orientation) {
            0 -> RectF(
                pathRectLocal.left,
                pathRectLocal.top,
                pathRectLocal.left + arcDiameter,
                pathRectLocal.top + arcDiameter,
            )
            1 -> RectF(
                pathRectLocal.left,
                pathRectLocal.bottom - arcDiameter,
                pathRectLocal.left + arcDiameter,
                pathRectLocal.bottom,
            )
            else -> throw IllegalStateException("Unknown orientation")
        }
        createPath()
    }


}
