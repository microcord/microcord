package com.discord.widgets.chat.input

import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Mirrors the live IME + navigation-bar bottom inset to [onBottomInsetChanged] on every frame of
 * the real system keyboard animation, instead of only its settled value — this is what turns an
 * instant layout jump into a smooth slide in lockstep with the actual keyboard motion.
 */
object SmoothKeyboardReactionHelper {
    fun install(view: View, onBottomInsetChanged: ((Int) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        ViewCompat.setWindowInsetsAnimationCallback(
            view,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    onBottomInsetChanged?.invoke(bottomInset(insets))
                    return insets
                }

                // An animation cut short (an attachment opening, a back gesture) never delivers its
                // last frame, which would leave the spacer at some mid-animation height. Settle on
                // what the window reports now.
                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    ViewCompat.getRootWindowInsets(view)?.let { onBottomInsetChanged?.invoke(bottomInset(it)) }
                }
            },
        )
    }

    // ime()'s bottom inset overlaps the nav bar's own region, which is already reserved elsewhere
    // (see WidgetHome.applyPanelInsets); subtract it out here too.
    private fun bottomInset(insets: WindowInsetsCompat): Int =
        (insets.getInsets(WindowInsetsCompat.Type.ime()).bottom -
            insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom).coerceAtLeast(0)
}
