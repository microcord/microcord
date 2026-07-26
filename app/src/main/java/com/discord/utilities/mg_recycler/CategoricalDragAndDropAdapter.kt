package com.discord.utilities.mg_recycler

import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min

/**
 * A [DragAndDropAdapter] whose rows belong to categories (for example channels inside a category,
 * or guilds inside a folder).
 *
 * Rows may only be reordered inside their own category, and positions reported to
 * [onNewPositions] restart at zero for every category, because the backend stores a per-category
 * position rather than a list-wide index.
 */
abstract class CategoricalDragAndDropAdapter<T : CategoricalDragAndDropAdapter.Payload>(
    recycler: RecyclerView,
) : DragAndDropAdapter<T>(recycler) {

    /** A reorderable row that also declares which category it lives in. */
    interface Payload : DragAndDropAdapter.Payload {
        val category: String
    }

    override fun isValidMove(fromPosition: Int, toPosition: Int): Boolean {
        if (!isMoveWithinBounds(fromPosition, toPosition)) return false
        val category = dataCopy[fromPosition].category
        return (min(fromPosition, toPosition)..max(fromPosition, toPosition))
            .all { dataCopy[it].category == category }
    }

    override fun computeChangedPositions(): Map<String, Int> {
        val changed = HashMap<String, Int>()
        if (dataCopy.isEmpty()) return changed

        var category = dataCopy[0].category
        var positionInCategory = 0
        for (payload in dataCopy) {
            if (payload.category != category) {
                category = payload.category
                positionInCategory = 0
            }
            if (origPositions[payload.key] != positionInCategory) {
                changed[payload.key] = positionInCategory
            }
            positionInCategory += 1
        }
        return changed
    }
}
