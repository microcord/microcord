package com.discord.widgets.chat.input

import android.annotation.TargetApi
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation

@TargetApi(Build.VERSION_CODES.R)
object SmoothKeyboardReactionHelper {
    fun install(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.setWindowInsetsAnimationCallback(Callback(view))
        }
    }

    private class Callback(private val view: View) :
        WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP) {

        override fun onProgress(
            insets: WindowInsets,
            runningAnimations: MutableList<WindowInsetsAnimation>,
        ): WindowInsets = insets

        override fun onStart(
            animation: WindowInsetsAnimation,
            bounds: WindowInsetsAnimation.Bounds,
        ): WindowInsetsAnimation.Bounds {
            view.translationY = if (view.rootWindowInsets.isVisible(WindowInsets.Type.ime())) {
                bounds.upperBound.bottom.toFloat()
            } else {
                -bounds.upperBound.bottom.toFloat()
            }
            view.animate()
                .setDuration(animation.durationMillis)
                .setInterpolator(animation.interpolator)
                .translationY(0f)
            return super.onStart(animation, bounds)
        }
    }
}
