package com.discord.utilities.mg_recycler

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.sign

/**
 * `ItemTouchHelper.Callback` for long-press reordering of a list.
 *
 * Only holders that implement [DraggableViewHolder] and currently answer `canDrag()` may be
 * picked up; swipe-to-dismiss is never enabled. Auto-scroll while dragging past the edge runs at a
 * constant [dragScrollSpeed] rather than the accelerating default, so dragging a guild across a
 * long server list stays controllable.
 */
class DragAndDropHelper @JvmOverloads constructor(
    private val adapter: Adapter,
    private val dragScrollSpeed: Int = DEFAULT_DRAG_SCROLL_SPEED,
) : ItemTouchHelper.Callback() {

    /** Reorder contract implemented by the backing adapter. */
    interface Adapter {
        /** Whether moving from [fromPosition] to [toPosition] is allowed. */
        fun isValidMove(fromPosition: Int, toPosition: Int): Boolean

        /** Applies an in-progress move; called continuously while dragging. */
        fun onItemMove(fromPosition: Int, toPosition: Int)

        /** Called once the drag gesture finishes so the result can be persisted. */
        fun onDrop()
    }

    /** Implemented by row view holders that participate in drag and drop. */
    interface DraggableViewHolder {
        fun canDrag(): Boolean
        fun onDragStateChanged(dragging: Boolean)
    }

    override fun isLongPressDragEnabled(): Boolean = true

    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int {
        val canDrag = viewHolder is DraggableViewHolder && viewHolder.canDrag()
        val dragFlags = if (canDrag) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0
        return makeMovementFlags(dragFlags, 0)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        source: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean {
        val from = source.adapterPosition
        val to = target.adapterPosition
        if (!adapter.isValidMove(from, to)) return false
        adapter.onItemMove(from, to)
        return true
    }

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            (viewHolder as DraggableViewHolder).onDragStateChanged(true)
        }
        super.onSelectedChanged(viewHolder, actionState)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        (viewHolder as DraggableViewHolder).onDragStateChanged(false)
        adapter.onDrop()
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun interpolateOutOfBoundsScroll(
        recyclerView: RecyclerView,
        viewSize: Int,
        viewSizeOutOfBounds: Int,
        totalSize: Int,
        msSinceStartScroll: Long,
    ): Int = dragScrollSpeed * viewSizeOutOfBounds.sign

    private companion object {
        private const val DEFAULT_DRAG_SCROLL_SPEED = 15
    }
}
