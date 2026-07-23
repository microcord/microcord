package com.discord.views.typing

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R

class TypingDots(context: Context, attrs: AttributeSet?) : ConstraintLayout(context, attrs, 0) {
    private val dots: List<TypingDot>
    private val dotsAnimationStaggerTimeMs = (resources.getInteger(R.integer.animation_time_standard) / 1.5).toLong()
    private var isRunning = false

    init {
        LayoutInflater.from(context).inflate(R.layout.typing_dots_view, this)
        dots = listOf(
            requireNotNull(findViewById(R.id.view_typing_dots_1)),
            requireNotNull(findViewById(R.id.view_typing_dots_2)),
            requireNotNull(findViewById(R.id.view_typing_dots_3)),
        )
        dots[2].onScaleDownCompleteListener = {
            if (isAttachedToWindow) start(isReplay = true)
        }
    }

    @JvmOverloads
    fun start(isReplay: Boolean = false) {
        if (isRunning && !isReplay) return
        val firstDelay = if (isReplay) dotsAnimationStaggerTimeMs else 0L
        dots.forEachIndexed { index, dot ->
            dot.start(firstDelay + index * dotsAnimationStaggerTimeMs)
        }
        isRunning = true
    }

    fun stop() {
        dots.forEach(TypingDot::stop)
        isRunning = false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }
}
