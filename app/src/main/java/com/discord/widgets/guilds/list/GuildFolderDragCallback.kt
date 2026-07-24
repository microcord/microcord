package com.discord.widgets.guilds.list

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class GuildFolderDragCallback(
    private val keyAt: (Int) -> GuildFolderReorder.EntryKey?,
    private val canDrag: (Int) -> Boolean,
    private val emit: (GuildFolderReorder.Operation) -> Unit,
    private val onDrop: () -> Unit,
    private val combine: (GuildFolderReorder.EntryKey, GuildFolderReorder.EntryKey) -> Boolean = { _, _ -> false },
) : ItemTouchHelper.Callback() {
    private var dragging = false

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        val position = viewHolder.bindingAdapterPosition
        val flags = if (position != RecyclerView.NO_POSITION && canDrag(position)) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0
        return makeMovementFlags(flags, 0)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        source: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean {
        val from = source.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        val sourceKey = keyAt(from) ?: return false
        val targetKey = keyAt(to) ?: return false
        val targetTop = target.itemView.y
        val sourceCenter = source.itemView.y + source.itemView.height / 2f
        if (sourceCenter in (targetTop + target.itemView.height * 0.4f)..(targetTop + target.itemView.height * 0.6f) &&
            combine(sourceKey, targetKey)) return true
        emit(if (to < from) GuildFolderReorder.Operation.MoveBefore(sourceKey, targetKey)
        else GuildFolderReorder.Operation.MoveAfter(sourceKey, targetKey))
        return true
    }

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) dragging = true
        if (actionState == ItemTouchHelper.ACTION_STATE_IDLE && dragging) {
            dragging = false
            onDrop()
        }
        super.onSelectedChanged(viewHolder, actionState)
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
    override fun isItemViewSwipeEnabled() = false
}
