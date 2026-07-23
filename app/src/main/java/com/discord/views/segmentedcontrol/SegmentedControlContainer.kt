package com.discord.views.segmentedcontrol

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

class SegmentedControlContainer(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private var segmentSelectedChangeListener: ((Int) -> Unit)? = null

    @JvmOverloads
    fun setup(selectedIndex: Int = 0) {
        for (index in 0 until childCount) {
            val segment = requireSegment(index)
            getChildAt(index).setOnClickListener { segmentSelectedChangeListener?.invoke(index) }
            segment.setSegmentSelected(index == selectedIndex)
        }
    }

    fun setSelectedIndex(selectedIndex: Int) {
        for (index in 0 until childCount) {
            requireSegment(index).setSegmentSelected(index == selectedIndex)
        }
    }

    fun setOnSegmentSelectedChangeListener(listener: (Int) -> Unit) {
        segmentSelectedChangeListener = listener
    }

    private fun requireSegment(index: Int): SegmentedControlSegment =
        getChildAt(index) as? SegmentedControlSegment
            ?: throw IllegalStateException("All children must be SegmentedControlSegments.")
}
