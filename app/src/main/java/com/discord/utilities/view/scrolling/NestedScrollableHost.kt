package com.discord.utilities.view.scrolling

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.sign

class NestedScrollableHost : FrameLayout {
    private var initialX = 0f
    private var initialY = 0f
    private val touchSlop: Int

    constructor(context: Context) : super(context) {
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        handleInterceptTouchEvent(event)
        return super.onInterceptTouchEvent(event)
    }

    private fun handleInterceptTouchEvent(event: MotionEvent) {
        val pager = findParentPager() ?: return
        if (!canChildScroll(pager.orientation, -1f) && !canChildScroll(pager.orientation, 1f)) return

        if (event.action == MotionEvent.ACTION_DOWN) {
            initialX = event.x
            initialY = event.y
            parent.requestDisallowInterceptTouchEvent(true)
        } else if (event.action == MotionEvent.ACTION_MOVE) {
            val dx = event.x - initialX
            val dy = event.y - initialY
            val scaledDx = abs(dx) * if (pager.orientation == ViewPager2.ORIENTATION_HORIZONTAL) 0.5f else 1f
            val scaledDy = abs(dy) * if (pager.orientation == ViewPager2.ORIENTATION_VERTICAL) 0.5f else 1f
            if (scaledDx > touchSlop || scaledDy > touchSlop) {
                val perpendicular = if (pager.orientation == ViewPager2.ORIENTATION_HORIZONTAL) {
                    scaledDy > scaledDx
                } else {
                    scaledDx > scaledDy
                }
                if (perpendicular) {
                    parent.requestDisallowInterceptTouchEvent(false)
                } else {
                    val delta = if (pager.orientation == ViewPager2.ORIENTATION_HORIZONTAL) dx else dy
                    parent.requestDisallowInterceptTouchEvent(canChildScroll(pager.orientation, delta))
                }
            }
        }
    }

    private fun canChildScroll(orientation: Int, delta: Float): Boolean {
        val child = getChildAt(0) ?: return false
        val direction = -delta.sign.toInt()
        return when (orientation) {
            ViewPager2.ORIENTATION_HORIZONTAL -> child.canScrollHorizontally(direction)
            ViewPager2.ORIENTATION_VERTICAL -> child.canScrollVertically(direction)
            else -> throw IllegalArgumentException("Invalid orientation: $orientation")
        }
    }

    private fun findParentPager(): ViewPager2? {
        var ancestor = parent
        while (ancestor is View && ancestor !is ViewPager2) ancestor = ancestor.parent
        return ancestor as? ViewPager2
    }
}
