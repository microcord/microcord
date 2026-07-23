package com.discord.utilities.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.appbar.AppBarLayout

class ContentResizingCoordinatorLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : CoordinatorLayout(context, attrs, defStyleAttr) {
    private var appBarLayout: AppBarLayout? = null
    private var content: View? = null
    private var currentVerticalOffset = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (appBarLayout == null && childCount >= 2) {
            val appBar = getChildAt(0) as AppBarLayout
            val contentView = getChildAt(1)
            appBarLayout = appBar
            content = contentView
            measureChild(appBar, widthMeasureSpec, heightMeasureSpec)
            appBar.offsetTopAndBottom(0)
            resizeContent(
                contentView,
                View.MeasureSpec.getSize(heightMeasureSpec),
                appBar.measuredHeight,
                0,
                widthMeasureSpec,
                heightMeasureSpec,
            )
            appBar.addOnOffsetChangedListener { _, offset ->
                currentVerticalOffset = offset
                resizeContent(
                    contentView,
                    measuredHeight,
                    appBar.measuredHeight,
                    offset,
                    widthMeasureSpec,
                    heightMeasureSpec,
                )
            }
            appBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    resizeContent(
                        contentView,
                        measuredHeight,
                        appBar.measuredHeight,
                        currentVerticalOffset,
                        widthMeasureSpec,
                        heightMeasureSpec,
                    )
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun resizeContent(
        content: View,
        containerHeight: Int,
        appBarHeight: Int,
        verticalOffset: Int,
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        content.layoutParams = content.layoutParams.apply {
            height = containerHeight - (appBarHeight + verticalOffset)
        }
        measureChild(content, widthMeasureSpec, heightMeasureSpec)
    }
}
