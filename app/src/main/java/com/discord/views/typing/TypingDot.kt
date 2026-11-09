package com.discord.views.typing

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.annotation.AnimRes
import com.discord.R

class TypingDot(context: Context, attrs: AttributeSet?) : View(context, attrs, 0) {
    // Loaded on first start: every chat, channel and DM row holds three dots, and most never animate,
    // so parsing six animation resources per row up front only slowed list inflation.
    private val scaleDown = lazy(LazyThreadSafetyMode.NONE) {
        load(R.anim.anim_typing_dots_scale_down) { onScaleDownCompleteListener?.invoke() }
    }
    private val scaleUp = lazy(LazyThreadSafetyMode.NONE) {
        load(R.anim.anim_typing_dots_scale_up) { startAnimation(scaleDown.value) }
    }
    var onScaleDownCompleteListener: (() -> Unit)? = null

    @JvmOverloads
    fun start(delay: Long = 0L) {
        val animation = scaleUp.value
        animation.startOffset = delay
        startAnimation(animation)
    }

    fun stop() {
        if (scaleUp.isInitialized()) scaleUp.value.cancel()
        if (scaleDown.isInitialized()) scaleDown.value.cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    private fun load(@AnimRes id: Int, onEnd: () -> Unit): Animation =
        AnimationUtils.loadAnimation(context, id).apply {
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) = Unit
                override fun onAnimationRepeat(animation: Animation?) = Unit
                override fun onAnimationEnd(animation: Animation?) = onEnd()
            })
        }
}
