package com.discord.rlottie

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.Display
import android.view.WindowManager
import androidx.appcompat.widget.AppCompatImageView

open class RLottieImageView(context: Context, attrs: AttributeSet?) : AppCompatImageView(context, attrs) {
    var rlottieDrawable: RLottieDrawable? = null
        private set
    private var attachedToWindow = false
    private var playing = false

    fun setAnimation(resId: Int, width: Int, height: Int) {
        rlottieDrawable = RLottieDrawable(
            context,
            resId,
            resId.toString(),
            width,
            height,
            display(context).refreshRate,
            false,
            null,
        ).also {
            it.setPlaybackMode(RLottieDrawable.PlaybackMode.LOOP)
            it.setAllowDecodeSingleFrame(true)
        }
        setImageDrawable(rlottieDrawable)
    }

    fun setRLottieDrawable(drawable: RLottieDrawable) {
        rlottieDrawable = drawable
        setImageDrawable(drawable)
    }

    fun playAnimation() {
        val drawable = rlottieDrawable ?: return
        playing = true
        if (attachedToWindow) drawable.start()
    }

    fun setPlaybackMode(mode: RLottieDrawable.PlaybackMode) {
        rlottieDrawable?.setPlaybackMode(mode)
    }

    fun setProgress(progress: Float) {
        rlottieDrawable?.setProgress(progress)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedToWindow = true
        if (playing) rlottieDrawable?.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attachedToWindow = false
        rlottieDrawable?.stop()
    }

    @SuppressLint("AnnotateVersionCheck")
    private fun display(context: Context): Display = if (Build.VERSION.SDK_INT >= 30) {
        requireNotNull(context.display)
    } else {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    }
}
