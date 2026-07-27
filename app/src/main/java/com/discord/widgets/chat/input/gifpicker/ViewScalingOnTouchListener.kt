package com.discord.widgets.chat.input.gifpicker

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.MotionEvent
import android.view.View

/**
 * Shrinks a view while it is held and springs it back on release or cancel.
 *
 * Always returns `false`, so the view's own click handling is untouched — this is decoration only.
 */
class ViewScalingOnTouchListener(private val scaleDownFactor: Float) : View.OnTouchListener {

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> animateTo(view, scaleDownFactor)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateTo(view, 1f)
        }
        return false
    }

    private fun animateTo(view: View, scale: Float) {
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", scale).apply {
            duration = SCALE_ANIMATION_DURATION_MS
        }
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", scale).apply {
            duration = SCALE_ANIMATION_DURATION_MS
        }
        AnimatorSet().apply { play(scaleX).with(scaleY) }.start()
    }

    private companion object {
        const val SCALE_ANIMATION_DURATION_MS = 200L
    }
}
