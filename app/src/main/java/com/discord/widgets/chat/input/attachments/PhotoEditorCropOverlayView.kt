package com.discord.widgets.chat.input.attachments

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class PhotoEditorCropOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val crop = RectF()
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * resources.displayMetrics.density }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var drag = NONE
    private var lastX = 0f
    private var lastY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = crop.set(w * 0.1f, h * 0.1f, w * 0.9f, h * 0.9f)

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), crop.top, shade)
        canvas.drawRect(0f, crop.bottom, width.toFloat(), height.toFloat(), shade)
        canvas.drawRect(0f, crop.top, crop.left, crop.bottom, shade)
        canvas.drawRect(crop.right, crop.top, width.toFloat(), crop.bottom, shade)
        canvas.drawRect(crop, border)
        val radius = 6 * resources.displayMetrics.density
        listOf(crop.left to crop.top, crop.right to crop.top, crop.left to crop.bottom, crop.right to crop.bottom)
            .forEach { (x, y) -> canvas.drawCircle(x, y, radius, handle) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val threshold = 32 * resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = when {
                    event.x < crop.left + threshold && event.y < crop.top + threshold -> TOP_LEFT
                    event.x > crop.right - threshold && event.y < crop.top + threshold -> TOP_RIGHT
                    event.x < crop.left + threshold && event.y > crop.bottom - threshold -> BOTTOM_LEFT
                    event.x > crop.right - threshold && event.y > crop.bottom - threshold -> BOTTOM_RIGHT
                    crop.contains(event.x, event.y) -> MOVE
                    else -> NONE
                }
                lastX = event.x; lastY = event.y
                return drag != NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX; val dy = event.y - lastY
                when (drag) {
                    MOVE -> crop.offset(dx.coerceIn(-crop.left, width - crop.right), dy.coerceIn(-crop.top, height - crop.bottom))
                    TOP_LEFT -> { crop.left = event.x.coerceIn(0f, crop.right - threshold); crop.top = event.y.coerceIn(0f, crop.bottom - threshold) }
                    TOP_RIGHT -> { crop.right = event.x.coerceIn(crop.left + threshold, width.toFloat()); crop.top = event.y.coerceIn(0f, crop.bottom - threshold) }
                    BOTTOM_LEFT -> { crop.left = event.x.coerceIn(0f, crop.right - threshold); crop.bottom = event.y.coerceIn(crop.top + threshold, height.toFloat()) }
                    BOTTOM_RIGHT -> { crop.right = event.x.coerceIn(crop.left + threshold, width.toFloat()); crop.bottom = event.y.coerceIn(crop.top + threshold, height.toFloat()) }
                }
                lastX = event.x; lastY = event.y; invalidate()
            }
        }
        return true
    }

    fun cropPercent() = RectF(crop.left / width, crop.top / height, crop.right / width, crop.bottom / height)

    private companion object { const val NONE = 0; const val MOVE = 1; const val TOP_LEFT = 2; const val TOP_RIGHT = 3; const val BOTTOM_LEFT = 4; const val BOTTOM_RIGHT = 5 }
}
