package com.discord.views.typing

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import com.discord.R

class TypingDot(context: Context, attrs: AttributeSet?) : View(context, attrs, 0) {
    private val scaleUpAnimation = AnimationUtils.loadAnimation(context, R.anim.anim_typing_dots_scale_up)
    private val scaleDownAnimation = AnimationUtils.loadAnimation(context, R.anim.anim_typing_dots_scale_down)
    var onScaleDownCompleteListener: (() -> Unit)? = null

    init {
        scaleUpAnimation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) = Unit
            override fun onAnimationRepeat(animation: Animation?) = Unit
            override fun onAnimationEnd(animation: Animation?) = startAnimation(scaleDownAnimation)
        })
        scaleDownAnimation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) = Unit
            override fun onAnimationRepeat(animation: Animation?) = Unit
            override fun onAnimationEnd(animation: Animation?) {
                onScaleDownCompleteListener?.invoke()
            }
        })
    }

    @JvmOverloads
    fun start(delay: Long = 0L) {
        scaleUpAnimation.startOffset = delay
        startAnimation(scaleUpAnimation)
    }

    fun stop() {
        scaleUpAnimation.cancel()
        scaleDownAnimation.cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }
}
