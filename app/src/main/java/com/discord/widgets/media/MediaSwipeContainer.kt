package com.discord.widgets.media

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

class MediaSwipeContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    var canStartGesture: () -> Boolean = { true }
    var onVerticalDrag: ((Float) -> Unit)? = null
    var onVerticalRelease: ((Float) -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var mode = NONE

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        process(event)
        return mode == VERTICAL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        process(event)
        return true
    }

    private fun process(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                mode = NONE
            }
            MotionEvent.ACTION_POINTER_DOWN -> mode = BLOCKED
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (mode == NONE && maxOf(abs(dx), abs(dy)) > slop) {
                    mode = if (!canStartGesture()) BLOCKED
                    else if (abs(dx) > abs(dy)) BLOCKED else VERTICAL
                }
                if (mode == VERTICAL) onVerticalDrag?.invoke(dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (mode == VERTICAL) onVerticalRelease?.invoke(dy)
                mode = NONE
            }
        }
    }

    private companion object { const val NONE = 0; const val VERTICAL = 1; const val BLOCKED = 2 }
}
