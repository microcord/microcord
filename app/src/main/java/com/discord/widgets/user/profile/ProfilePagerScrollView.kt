package com.discord.widgets.user.profile

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.widget.NestedScrollView
import kotlin.math.abs

class ProfilePagerScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs) {
    var onHorizontalDrag: ((distance: Float) -> Unit)? = null
    var onHorizontalRelease: ((distance: Float, commit: Boolean) -> Unit)? = null
    private val threshold = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var draggingHorizontally = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                draggingHorizontally = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!draggingHorizontally && abs(dx) >= threshold && abs(dx) > abs(dy) * 1.5f) {
                    draggingHorizontally = true
                }
                if (draggingHorizontally) onHorizontalDrag?.invoke(dx)
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                if (draggingHorizontally) onHorizontalRelease?.invoke(dx, abs(dx) >= width * 0.2f)
                draggingHorizontally = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (draggingHorizontally) onHorizontalRelease?.invoke(0f, false)
                draggingHorizontally = false
            }
        }
        return super.dispatchTouchEvent(event)
    }
}
