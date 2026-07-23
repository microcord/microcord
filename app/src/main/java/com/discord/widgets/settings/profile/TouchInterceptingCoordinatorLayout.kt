package com.discord.widgets.settings.profile

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.coordinatorlayout.widget.CoordinatorLayout

class TouchInterceptingCoordinatorLayout : CoordinatorLayout {
    var onInterceptTouchEvent: ((MotionEvent?) -> Boolean)? = null

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    override fun onInterceptTouchEvent(event: MotionEvent?): Boolean =
        onInterceptTouchEvent?.invoke(event) ?: super.onInterceptTouchEvent(event)
}
