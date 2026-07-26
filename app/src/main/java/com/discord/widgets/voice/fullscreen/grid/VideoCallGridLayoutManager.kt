package com.discord.widgets.voice.fullscreen.grid

import android.content.Context
import androidx.recyclerview.widget.RecyclerView

/**
 * The call grid's layout manager.
 *
 * Adds two things to [ResizingGridLayoutManager]:
 *
 * * a fixed [spanCount] that the host recomputes on rotation, rather than the one baked into the
 *   superclass at construction;
 * * three recycler-lengths of extra layout space in each direction, so tiles just off screen are
 *   already attached and their video renderers are producing frames before the user scrolls to
 *   them. [recyclerviewSizeProvider] returns `0` before the recycler has been measured, in which
 *   case the default heuristic applies.
 */
class VideoCallGridLayoutManager(
    private val recyclerviewSizeProvider: () -> Int,
    private val spanCount: Int,
    private val onLayoutCompletedEvent: ((VideoCallGridLayoutManager, RecyclerView.State?) -> Unit)?,
    orientation: Int,
    context: Context,
) : ResizingGridLayoutManager(context, spanCount, orientation) {

    override fun getSpanCount(): Int = spanCount

    protected override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
        val recyclerviewSize = recyclerviewSizeProvider()
        if (recyclerviewSize > 0) {
            extraLayoutSpace[0] = recyclerviewSize * EXTRA_LAYOUT_SCREENS
            extraLayoutSpace[1] = recyclerviewSize * EXTRA_LAYOUT_SCREENS
        } else {
            super.calculateExtraLayoutSpace(state, extraLayoutSpace)
        }
    }

    override fun onLayoutCompleted(state: RecyclerView.State?) {
        super.onLayoutCompleted(state)
        onLayoutCompletedEvent?.invoke(this, state)
    }

    private companion object {
        const val EXTRA_LAYOUT_SCREENS = 3
    }
}
