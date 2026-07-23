package com.discord.views.calls

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.provider.Settings
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.discord.R

class SpeakerPulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val interpolator = PathInterpolator(0.4f, 0f, 1f, 1f)
    private var inner: ImageView? = null
    private var outer: ImageView? = null
    private var isPulsing = false
    private var hasAnimated = false

    init {
        clipChildren = false
        clipToOutline = false
        clipToPadding = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isPulsing) startAnimating()
    }

    override fun onDetachedFromWindow() {
        stopAnimations(false)
        super.onDetachedFromWindow()
    }

    fun setPulsing(value: Boolean) {
        if (isPulsing == value) return
        isPulsing = value
        if (value) startAnimating() else stopAnimations(hasAnimated)
    }

    private fun startAnimating() {
        if (!isAttachedToWindow || animationsDisabled()) return
        hasAnimated = true
        stopAnimations(false)
        animatePulse(innerView(), 200, 500)
        animatePulse(outerView(), 700, 0)
    }

    private fun animatePulse(view: View, startDelay: Long, fadeDelay: Long) {
        if (!isPulsing || !isAttachedToWindow) return
        view.animate().alpha(0.16f).setInterpolator(interpolator).setStartDelay(startDelay)
            .setDuration(500).setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.animate().alpha(0f).setInterpolator(interpolator).setStartDelay(fadeDelay)
                        .setDuration(1_000).setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                animatePulse(view, startDelay, fadeDelay)
                            }
                        }).start()
                }
            }).start()
    }

    private fun stopAnimations(fade: Boolean) {
        listOfNotNull(inner, outer).forEach {
            it.animate().setListener(null).cancel()
            if (fade) it.animate().alpha(0f).setDuration(500).start() else it.alpha = 0f
        }
    }

    private fun pulseView(index: Int): ImageView {
        val actualChild = getChildAt(childCount - 1)
        val extra = paddingLeft * (index + 1)
        val size = actualChild.layoutParams?.width?.takeIf { it > 0 }?.plus(extra) ?: LayoutParams.MATCH_PARENT
        return ImageView(context).also {
            it.alpha = 0f
            it.setImageResource(R.drawable.drawable_circle_brand)
            addView(it, (childCount - 1).coerceAtLeast(0), LayoutParams(size, size, Gravity.CENTER))
        }
    }

    private fun innerView() = inner ?: pulseView(0).also { inner = it }
    private fun outerView() = outer ?: pulseView(1).also { outer = it }

    private fun animationsDisabled() = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
}
