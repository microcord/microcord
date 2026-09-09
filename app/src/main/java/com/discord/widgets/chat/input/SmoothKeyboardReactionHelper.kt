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
                    // ime()'s bottom inset overlaps the nav bar's own region, which is already
                    // reserved elsewhere (see WidgetHome.applyPanelInsets); subtract it out here too.
                    val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                    onBottomInsetChanged?.invoke((ime - navBar).coerceAtLeast(0))
                    return insets
                }
            },
        )
    }
}
