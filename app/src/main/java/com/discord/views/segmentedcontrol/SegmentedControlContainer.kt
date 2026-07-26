package com.discord.views.segmentedcontrol

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * A row of mutually exclusive segments.
 *
 * Selection is controlled by the owner rather than by the container: a tap
 * reports the segment index through [setOnSegmentSelectedChangeListener], and the
 * owner decides what becomes selected by calling [setSelectedIndex]. Each segment
 * is wired as it is added, so a control inflated from XML responds to taps
 * without the owner having to remember a separate setup call.
 */
class SegmentedControlContainer(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private var segmentSelectedChangeListener: ((Int) -> Unit)? = null
    private var selectedIndex = 0

    // Every addView overload funnels through this one, including layout inflation,
    // so wiring here covers both XML and programmatic segments on every API level.
    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams?) {
        super.addView(child, index, params)
        wire(child)
    }

    /**
     * Selects [selectedIndex] and re-applies the tap wiring. Children added through
     * the normal view APIs are already wired; this exists so an owner can reset the
     * control without tracking what it previously selected.
     */
    @JvmOverloads
    fun setup(selectedIndex: Int = 0) {
        for (index in 0 until childCount) {
            wire(getChildAt(index))
        }
        setSelectedIndex(selectedIndex)
    }

    private fun wire(child: View) {
        val segment = child.asSegment()
        child.setOnClickListener { segmentSelectedChangeListener?.invoke(indexOfChild(child)) }
        segment.setSegmentSelected(indexOfChild(child) == selectedIndex)
    }

    fun setSelectedIndex(selectedIndex: Int) {
        this.selectedIndex = selectedIndex
        for (index in 0 until childCount) {
            getChildAt(index).asSegment().setSegmentSelected(index == selectedIndex)
        }
    }

    fun setOnSegmentSelectedChangeListener(listener: (Int) -> Unit) {
        segmentSelectedChangeListener = listener
    }

    private fun View.asSegment(): SegmentedControlSegment = this as? SegmentedControlSegment
        ?: throw IllegalStateException("All children must be SegmentedControlSegments.")
}
