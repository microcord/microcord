package com.discord.utilities.view

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.updateLayoutParams
import kotlin.math.abs

/**
 * Lets [handle] resize [target] the way RN's sheets do: drag or flick it up to grow, down to shrink
 * back, or tap it to toggle. Shrinking returns to the height [target] had when it started growing;
 * [expanded] is read at the start of every drag, as the room it measures can change in between.
 */
class HeightDragHandle(
    private val target: View,
    handle: View,
    private val expanded: () -> Int,
) {
    var isExpanded = false
        private set

    private var collapsed = 0
    private var animator: ValueAnimator? = null

    private val touchSlop = ViewConfiguration.get(handle.context).scaledTouchSlop
    private var startY = 0f
    private var startHeight = 0
    private var max = 0

    init {
        handle.setOnTouchListener { _, event -> onDrag(event) }
    }

    /** Feeds a drag from anywhere, starting with its `ACTION_DOWN`; the handle's own touches come here. */
    fun onDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                if (!isExpanded) collapsed = target.height
                startY = event.rawY
                startHeight = target.height
                max = expanded()
            }
            MotionEvent.ACTION_MOVE -> if (max > collapsed) target.updateLayoutParams {
                height = (startHeight + (startY - event.rawY)).toInt().coerceIn(collapsed, max)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Settles the way the finger was going, so a short flick is enough either way.
                val moved = startY - event.rawY
                animateTo(if (abs(moved) < touchSlop) !isExpanded else moved > 0)
            }
        }
        return true
    }

    /** Grows or shrinks [target] without a drag, as when opening something that wants the room. */
    fun settle(expand: Boolean) {
        if (!isExpanded) collapsed = target.height
        animateTo(expand)
    }

    private fun animateTo(expand: Boolean) {
        val to = if (expand) expanded() else collapsed
        if (to <= 0 || (expand && to <= collapsed)) return
        isExpanded = expand
        animator?.cancel()
        animator = ValueAnimator.ofInt(target.height, to).apply {
            duration = 200L
            addUpdateListener { target.updateLayoutParams { height = it.animatedValue as Int } }
            start()
        }
    }

    /** Forgets the expansion once [target] has been sized from outside, as on reopening. */
    fun reset() {
        animator?.cancel()
        isExpanded = false
    }
}
