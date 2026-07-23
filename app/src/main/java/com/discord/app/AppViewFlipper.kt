package com.discord.app

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.ViewFlipper
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import com.discord.R

class AppViewFlipper(context: Context, attrs: AttributeSet) : ViewFlipper(context, attrs) {
    private val previewedChildIndex: Int

    init {
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val flipper = view as AppViewFlipper
            for (index in 0 until flipper.childCount) {
                ViewCompat.dispatchApplyWindowInsets(flipper.getChildAt(index), insets)
            }
            insets.consumeSystemWindowInsets()
        }

        previewedChildIndex = context.obtainStyledAttributes(attrs, R.styleable.AppViewFlipper).run {
            try {
                getInt(R.styleable.AppViewFlipper_avf_previewed_child_index, 0)
            } finally {
                recycle()
            }
        }
    }

    override fun setDisplayedChild(whichChild: Int) {
        if (displayedChild != whichChild) super.setDisplayedChild(whichChild)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        if (isInEditMode) displayedChild = previewedChildIndex
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (parent !is CoordinatorLayout) return

        for (index in 0 until childCount) {
            var originalPadding: Rect? = null
            ViewCompat.setOnApplyWindowInsetsListener(getChildAt(index)) { view, insets ->
                val padding = originalPadding ?: Rect(
                    view.paddingLeft,
                    view.paddingTop,
                    view.paddingRight,
                    view.paddingBottom,
                ).also { originalPadding = it }
                view.setPadding(
                    padding.left + insets.systemWindowInsetLeft,
                    padding.top,
                    padding.right + insets.systemWindowInsetRight,
                    padding.bottom + insets.systemWindowInsetTop + insets.systemWindowInsetBottom,
                )
                insets
            }
        }
    }
}
