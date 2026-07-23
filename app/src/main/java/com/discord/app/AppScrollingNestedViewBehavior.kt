package com.discord.app

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.AppBarLayout

class AppScrollingNestedViewBehavior(
    context: Context,
    attrs: AttributeSet,
) : AppBarLayout.ScrollingViewBehavior(context, attrs) {
    override fun onApplyWindowInsets(
        coordinatorLayout: CoordinatorLayout,
        child: View,
        insets: WindowInsetsCompat,
    ): WindowInsetsCompat {
        ViewCompat.setOnApplyWindowInsetsListener(child, NO_OP_WINDOW_INSETS_LISTENER)
        return NO_OP_WINDOW_INSETS_LISTENER.onApplyWindowInsets(child, insets)
    }

    private companion object {
        val NO_OP_WINDOW_INSETS_LISTENER = OnApplyWindowInsetsListener { _, insets -> insets }
    }
}
