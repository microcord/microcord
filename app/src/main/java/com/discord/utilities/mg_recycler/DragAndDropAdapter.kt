package com.discord.utilities.mg_recycler

import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter base for reorderable lists.
 *
 * Reordering happens optimistically on a working copy of the rows ([dataCopy]) so the list follows
 * the finger without a round trip. [origPositions] records the positions the rows arrived with, so
 * when the gesture ends only the rows that actually moved are reported to [onNewPositions] and
 * persisted.
 *
 * Deferred diffing is disabled: a drag produces one update per move event and those must be
 * applied in order, on the spot.
 */
abstract class DragAndDropAdapter<T : DragAndDropAdapter.Payload>(
    recyclerView: RecyclerView,
) : MGRecyclerAdapterSimple<T>(recyclerView, deferredDiffs = false), DragAndDropHelper.Adapter {

    /** A row that knows its own position within the list. */
    interface Payload : MGRecyclerDataPayload {
        val position: Int
    }

    protected var dataCopy: MutableList<T> = ArrayList()
    protected var origPositions: MutableMap<String, Int> = HashMap()

    override fun setData(data: List<T>) {
        computeOriginalPositions(data)
        setAndCopyData(data)
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int) {
        dataCopy.add(toPosition, dataCopy.removeAt(fromPosition))
        setAndCopyData(dataCopy)
    }

    override fun onDrop() {
        if (dataCopy.isEmpty()) return
        val changedPositions = computeChangedPositions()
        if (changedPositions.isNotEmpty()) onNewPositions(changedPositions)
    }

    /** Reports the rows whose position differs from the one they were last given, keyed by row key. */
    open fun computeChangedPositions(): Map<String, Int> {
        val changed = HashMap<String, Int>()
        dataCopy.forEachIndexed { index, payload ->
            val key = payload.key
            if (origPositions[key] != index) changed[key] = index
        }
        return changed
    }

    protected fun isMoveWithinBounds(fromPosition: Int, toPosition: Int): Boolean {
        if (dataCopy.isEmpty()) return false
        val bounds = dataCopy.indices
        return fromPosition in bounds && toPosition in bounds
    }

    /** Persist the new ordering; called once per completed drag gesture. */
    abstract fun onNewPositions(newPositions: Map<String, Int>)

    private fun computeOriginalPositions(data: List<T>) {
        origPositions.clear()
        for (payload in data) origPositions[payload.key] = payload.position
    }

    private fun setAndCopyData(data: List<T>) {
        super.setData(data)
        dataCopy = ArrayList(data)
    }
}
