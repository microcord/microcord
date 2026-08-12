package com.discord.widgets.chat.input.attachments

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.atan2
import kotlin.math.hypot

internal class PhotoEditorOverlayTouchListener(
    private val view: View,
    private val onLongPress: () -> Unit,
) : View.OnTouchListener {
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val down = PointF()
    private var startX = 0f
    private var startY = 0f
    private var startScale = 1f
    private var startRotation = 0f
    private var startDistance = 0f
    private var startAngle = 0f
    private var longPressed = false
    private var moved = false
    private var lastTapTime = 0L
    private val longPress = Runnable { longPressed = true; view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onLongPress() }

    override fun onTouch(target: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down.set(event.rawX, event.rawY)
                startX = target.x
                startY = target.y
                longPressed = false
                moved = false
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longPress)
                if (event.pointerCount >= 2) {
                    startDistance = distance(event)
                    startAngle = angle(event)
                    startScale = target.scaleX
                    startRotation = target.rotation
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && startDistance > 0f) {
                    handler.removeCallbacks(longPress)
                    val scale = (startScale * distance(event) / startDistance).coerceIn(0.2f, 6f)
                    target.scaleX = scale
                    target.scaleY = scale
                    target.rotation = startRotation + angle(event) - startAngle
                } else {
                    val dx = event.rawX - down.x
                    val dy = event.rawY - down.y
                    if (hypot(dx, dy) > touchSlop) { moved = true; handler.removeCallbacks(longPress) }
                    target.x = startX + dx
                    target.y = startY + dy
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (!moved && !longPressed) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastTapTime <= ViewConfiguration.getDoubleTapTimeout()) {
                        val scale = (target.scaleX * 1.35f).coerceAtMost(6f)
                        target.scaleX = scale
                        target.scaleY = scale
                        lastTapTime = 0L
                    } else {
                        lastTapTime = now
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            MotionEvent.ACTION_POINTER_UP -> startDistance = 0f
        }
        return true
    }

    private fun distance(event: MotionEvent) = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
    private fun angle(event: MotionEvent) = Math.toDegrees(atan2((event.getY(1) - event.getY(0)).toDouble(), (event.getX(1) - event.getX(0)).toDouble())).toFloat()
}
