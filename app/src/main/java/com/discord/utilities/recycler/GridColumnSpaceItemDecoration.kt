package com.discord.utilities.recycler

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager

class GridColumnSpaceItemDecoration(
    private val columnSpacePx: Int,
    private val columnCount: Int,
) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        super.getItemOffsets(outRect, view, parent, state)
        // A staggered grid puts each item in whichever column is shortest, so the position says
        // nothing about the column; going by it overlapped tiles from the second row on.
        val column = when (val params = view.layoutParams) {
            is StaggeredGridLayoutManager.LayoutParams -> if (params.isFullSpan) columnCount - 1 else params.spanIndex
            else -> parent.getChildAdapterPosition(view) % columnCount
        }
        outRect.set(
            outRect.left,
            outRect.top,
            if (column == columnCount - 1) 0 else columnSpacePx,
            outRect.bottom,
        )
    }
}
