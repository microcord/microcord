package com.discord.utilities.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Holds content that [sizeHandle] resizes. While it is expanded, pulling down on content already
 * scrolled to its top shrinks it instead, as RN's sheets do; anywhere else the content scrolls (or
 * refreshes) as usual.
 */
class PullToShrinkLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var sizeHandle: HeightDragHandle? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        val handle = sizeHandle ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - downY
                if (handle.isExpanded && dy > touchSlop && dy > Math.abs(event.x - downX) &&
                    !canScrollUpAt(this, downX, downY)
                ) {
                    dragging = true
                    // The drag starts here, not at the press, so the tray does not jump by the slop.
                    val start = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_DOWN }
                    handle.onDrag(start)
                    start.recycle()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        sizeHandle?.onDrag(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) dragging = false
        return true
    }

    /** Whether anything shown under ([x], [y]) can still scroll back towards its top. */
    private fun canScrollUpAt(view: View, x: Float, y: Float): Boolean {
        if (view !== this && view.canScrollVertically(-1)) return true
        if (view !is ViewGroup) return false
        for (i in view.childCount - 1 downTo 0) {
            val child = view.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val cx = x + view.scrollX - child.left
            val cy = y + view.scrollY - child.top
            if (cx < 0 || cy < 0 || cx >= child.width || cy >= child.height) continue
            if (canScrollUpAt(child, cx, cy)) return true
        }
        return false
    }
}
