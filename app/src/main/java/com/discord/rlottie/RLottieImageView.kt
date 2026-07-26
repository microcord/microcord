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

    @JvmOverloads
    fun setAnimation(
        resId: Int,
        width: Int,
        height: Int,
        playbackMode: RLottieDrawable.PlaybackMode = RLottieDrawable.PlaybackMode.LOOP,
    ) {
        val drawable = RLottieDrawable(
            context,
            resId,
            resId.toString(),
            width,
            height,
            display(context).refreshRate,
            false,
            null,
        ).also {
            it.setPlaybackMode(playbackMode)
            it.setAllowDecodeSingleFrame(true)
        }
        setRLottieDrawable(drawable)
    }

    fun setRLottieDrawable(drawable: RLottieDrawable) {
        rlottieDrawable?.removeParentView(this)
        rlottieDrawable = drawable
        if (attachedToWindow) drawable.addParentView(this)
        setImageDrawable(drawable)
    }

    fun playAnimation() {
        val drawable = rlottieDrawable ?: return
        playing = true
        if (attachedToWindow) drawable.start()
    }

    fun stopAnimation() {
        playing = false
        rlottieDrawable?.stop()
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
        rlottieDrawable?.let {
            it.addParentView(this)
            if (playing) it.start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attachedToWindow = false
        // Unregistering lets the drawable stop itself instead of decoding frames
        // for a view that is no longer on screen.
        rlottieDrawable?.let {
            it.stop()
            it.removeParentView(this)
        }
    }

    @SuppressLint("AnnotateVersionCheck")
    private fun display(context: Context): Display = if (Build.VERSION.SDK_INT >= 30) {
        requireNotNull(context.display)
    } else {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    }
}
