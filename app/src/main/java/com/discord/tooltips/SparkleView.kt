package com.discord.tooltips

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.discord.R

class SparkleView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    private val image: ImageView
    private val sparkleDrawable by lazy(LazyThreadSafetyMode.NONE) {
        AnimatedVectorDrawableCompat.create(context, R.drawable.sparkle_animated_vector)
    }
    private val restart = Runnable { if (isAttachedToWindow) sparkleDrawable?.start() }

    init {
        LayoutInflater.from(context).inflate(R.layout.sparkle_view, this)
        image = requireNotNull(findViewById(R.id.sparkle_view_image))
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        image.setImageDrawable(sparkleDrawable)
        sparkleDrawable?.registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) {
                postDelayed(restart, 500L)
            }
        })
        sparkleDrawable?.start()
    }

    fun stop() {
        sparkleDrawable?.stop()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(restart)
        super.onDetachedFromWindow()
    }
}
