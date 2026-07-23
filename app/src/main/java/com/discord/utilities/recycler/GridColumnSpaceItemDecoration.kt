package com.discord.utilities.recycler

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

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
        val position = parent.getChildAdapterPosition(view)
        outRect.set(
            outRect.left,
            outRect.top,
            if (position % columnCount == columnCount - 1) 0 else columnSpacePx,
            outRect.bottom,
        )
    }
}
