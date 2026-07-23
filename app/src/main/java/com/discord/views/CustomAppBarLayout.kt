package com.discord.views

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.appbar.AppBarLayout
import kotlin.math.abs

class CustomAppBarLayout(context: Context, attrs: AttributeSet?) : AppBarLayout(context, attrs) {
    private var percentCollapsed = 0f
    private var onPercentCollapsedCallback: (Float) -> Unit = {}
    private val offsetListener = OnOffsetChangedListener { appBar, verticalOffset ->
        val percent = if (appBar.totalScrollRange <= 0) {
            1f
        } else {
            abs(verticalOffset).toFloat() / appBar.totalScrollRange
        }
        if (percent != percentCollapsed) {
            percentCollapsed = percent
            onPercentCollapsedCallback(percent)
        }
    }

    val isCollapsed: Boolean
        get() = percentCollapsed == 1f

    fun setOnPercentCollapsedCallback(callback: (Float) -> Unit) {
        onPercentCollapsedCallback = callback
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        addOnOffsetChangedListener(offsetListener)
    }

    override fun onDetachedFromWindow() {
        removeOnOffsetChangedListener(offsetListener)
        super.onDetachedFromWindow()
    }
}
