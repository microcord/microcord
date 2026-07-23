package com.discord.utilities.dimmer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ProgressBar
import android.widget.RelativeLayout

class DimmerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr) {
    private val dimmerProgressBar = ProgressBar(context).also {
        it.visibility = View.GONE
        addView(it)
    }
    private var animationDirection = 0

    @JvmOverloads
    fun setDimmed(dimmed: Boolean, showProgressBar: Boolean = true) {
        if (dimmed) fadeIn() else fadeOut()
        dimmerProgressBar.visibility =
            if (dimmed && showProgressBar) View.VISIBLE else View.GONE
    }

    private fun fadeIn() {
        if (animationDirection == 1) return
        animate().cancel()
        animationDirection = 0
        if (visibility == View.VISIBLE) {
            alpha = 1f
            return
        }
        alpha = 0f
        visibility = View.VISIBLE
        animationDirection = 1
        animate().alpha(1f).setDuration(350L).setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(cleanupListener()).start()
    }

    private fun fadeOut() {
        if (animationDirection == -1) return
        animate().cancel()
        animationDirection = 0
        if (visibility != View.VISIBLE) return
        animationDirection = -1
        animate().alpha(0f).setDuration(350L).setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = View.GONE
                    animationDirection = 0
                }

                override fun onAnimationCancel(animation: Animator) {
                    animationDirection = 0
                }
            }).start()
    }

    private fun cleanupListener() = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            animationDirection = 0
        }

        override fun onAnimationCancel(animation: Animator) {
            animationDirection = 0
        }
    }
}
