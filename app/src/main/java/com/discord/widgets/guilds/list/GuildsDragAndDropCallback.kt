package com.discord.widgets.guilds.list

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * Long-press drag behaviour for the guild sidebar.
 *
 * Everything that needs to know about the item list is delegated to [Controller] (implemented by
 * [WidgetGuildListAdapter]); this class only supplies the ItemTouchHelper policy:
 *
 * * vertical drag only, and only for holders that report [DraggableViewHolder.canDrag],
 * * a 20 % move threshold instead of the default 50 %, so a guild starts moving as soon as it
 *   overlaps its neighbour rather than after passing its centre,
 * * a constant 20 px/frame edge scroll, because the default speed curve is tuned for full-width
 *   rows and crawls in a one-icon-wide list,
 * * no swipe.
 */
class GuildsDragAndDropCallback(private val controller: Controller) : ItemTouchHelper.Callback() {

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int {
        val draggable = (viewHolder as? DraggableViewHolder)?.canDrag() == true
        val dragFlags = if (draggable) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0
        return makeMovementFlags(dragFlags, 0)
    }

    override fun chooseDropTarget(
        selected: RecyclerView.ViewHolder,
        dropTargets: MutableList<RecyclerView.ViewHolder>,
        curX: Int,
        curY: Int,
    ): RecyclerView.ViewHolder? = controller.chooseDropTarget(selected, dropTargets, curX, curY)

    override fun getMoveThreshold(viewHolder: RecyclerView.ViewHolder): Float = MOVE_THRESHOLD

    override fun interpolateOutOfBoundsScroll(
        recyclerView: RecyclerView,
        viewSize: Int,
        viewSizeOutOfBounds: Int,
        totalSize: Int,
        msSinceStartScroll: Long,
    ): Int = Math.signum(viewSizeOutOfBounds.toFloat()).toInt() * DEFAULT_DRAG_SCROLL_SPEED

    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun isLongPressDragEnabled(): Boolean = true

    override fun onMove(
        recyclerView: RecyclerView,
        source: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean = controller.onMove(recyclerView, source, target)

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        when (actionState) {
            ItemTouchHelper.ACTION_STATE_DRAG -> viewHolder?.let(controller::onDragStarted)
            ItemTouchHelper.ACTION_STATE_IDLE -> controller.onDrop()
        }
        super.onSelectedChanged(viewHolder, actionState)
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    interface Controller {
        fun chooseDropTarget(
            selected: RecyclerView.ViewHolder,
            dropTargets: MutableList<RecyclerView.ViewHolder>,
            curX: Int,
            curY: Int,
        ): RecyclerView.ViewHolder?

        fun onDragStarted(viewHolder: RecyclerView.ViewHolder)

        fun onDrop()

        fun onMove(
            recyclerView: RecyclerView,
            source: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean
    }

    /** Implemented by the guild and folder holders; pending and lurked rows report `false`. */
    interface DraggableViewHolder {
        fun canDrag(): Boolean
    }

    private companion object {
        private const val MOVE_THRESHOLD = 0.2f
        private const val DEFAULT_DRAG_SCROLL_SPEED = 20
    }
}
