package com.discord.app

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.AppBarLayout

class AppScrollingViewBehavior(
    context: Context,
    attrs: AttributeSet,
) : AppBarLayout.ScrollingViewBehavior(context, attrs) {
    private val onApplyWindowInsetsListener = object : OnApplyWindowInsetsListener {
        private var initialPadding: Rect? = null

        override fun onApplyWindowInsets(view: View, insets: WindowInsetsCompat): WindowInsetsCompat {
            val padding = initialPadding ?: Rect(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                view.paddingBottom,
            ).also { initialPadding = it }

            view.setPadding(
                padding.left + insets.systemWindowInsetLeft,
                padding.top,
                padding.right + insets.systemWindowInsetRight,
                padding.bottom + insets.systemWindowInsetBottom + insets.systemWindowInsetTop,
            )
            return insets
        }
    }

    override fun onApplyWindowInsets(
        coordinatorLayout: CoordinatorLayout,
        child: View,
        insets: WindowInsetsCompat,
    ): WindowInsetsCompat {
        ViewCompat.setOnApplyWindowInsetsListener(child, NO_OP_WINDOW_INSETS_LISTENER)
        onApplyWindowInsetsListener.onApplyWindowInsets(child, insets)
        return insets
    }

    private companion object {
        val NO_OP_WINDOW_INSETS_LISTENER = OnApplyWindowInsetsListener { _, insets -> insets }
    }
}
