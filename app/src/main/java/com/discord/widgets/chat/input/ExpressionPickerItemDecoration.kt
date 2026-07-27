package com.discord.widgets.chat.input

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Adds breathing room under the **last row of each section** in an expression grid.
 *
 * Not a uniform bottom margin: a gap under every row would space the emoji or stickers out; the
 * gap belongs only between the final row of one section and the next section's header. Headers
 * themselves get nothing, since they carry their own padding.
 *
 * "Last row" is derived from the item's offset within its section against the column count, so it
 * is correct for a partially-filled final row too.
 *
 * @param headerIndices adapter positions of the section headers, ascending.
 */
class ExpressionPickerItemDecoration(
    private val numOfColumns: Int,
    private val headerIndices: List<Int>,
    private val bottomPaddingForLastRow: Int,
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        super.getItemOffsets(outRect, view, parent, state)
        if (headerIndices.isEmpty()) return

        val position = parent.getChildAdapterPosition(view)
        if (position in headerIndices) return

        val headerPosition = getHeaderPositionForItem(position)
        val nextHeaderPosition = getNextHeaderPositionForItem(position) ?: state.itemCount
        val itemsInSection = nextHeaderPosition - headerPosition - 1
        val fullRows = itemsInSection / numOfColumns - if (itemsInSection % numOfColumns == 0) 1 else 0
        val isInLastRow = position - headerPosition - 1 >= fullRows * numOfColumns

        outRect.bottom = if (isInLastRow) bottomPaddingForLastRow else 0
    }

    private fun getHeaderPositionForItem(itemPosition: Int): Int =
        headerIndices.lastOrNull { itemPosition >= it } ?: 0

    private fun getNextHeaderPositionForItem(itemPosition: Int): Int? =
        headerIndices.firstOrNull { itemPosition <= it }
}
