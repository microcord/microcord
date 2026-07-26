package com.discord.widgets.servers

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.app.AppLog
import com.discord.utilities.mg_recycler.CategoricalDragAndDropAdapter
import com.discord.utilities.mg_recycler.DragAndDropHelper
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

/**
 * The flat channel list used by the server-settings channel screens: a category header row followed
 * by the channels inside it.
 *
 * Reference: `com.discord.widgets.servers.SettingsChannelListAdapter`. Rows are draggable when
 * [isDraggable] is set *and* the row's own permission flag allows it, so a member who may reorder
 * one category cannot drag rows out of another. `WidgetServerNotificationsOverrideSelector` builds
 * it read-only and uses [onClickListener] alone.
 *
 * Reordering does not go through [CategoricalDragAndDropAdapter]'s key-based reporting: a channel
 * may change *category* as well as position, so [computeChangedPositions] reports channel ids to
 * [setPositionUpdateListener] as [UpdatedPosition] (new position + new parent) and returns an empty
 * map, exactly as the reference does.
 */
class SettingsChannelListAdapter(
    recycler: RecyclerView,
    isDraggable: Boolean = true,
) : CategoricalDragAndDropAdapter<CategoricalDragAndDropAdapter.Payload>(recycler) {

    /** New position of a moved channel, and the category it landed in (`null` for uncategorised). */
    data class UpdatedPosition(val position: Int, val parentId: Long?)

    data class CategoryItem(
        val name: String?,
        val id: Long,
        private val pos: Int,
        val isDraggable: Boolean,
        val canManageCategory: Boolean,
        val canManageChannelsOfCategory: Boolean,
    ) : CategoricalDragAndDropAdapter.Payload {
        override val type: Int get() = TYPE_CATEGORY
        override val key: String get() = id.toString()
        override val position: Int get() = pos

        // Every row reports the same category, so a drag is bounded by the permission checks in
        // `isValidMove` rather than by `CategoricalDragAndDropAdapter`'s category equality.
        override val category: String get() = type.toString()
    }

    data class ChannelItem(
        val channel: Channel,
        val isDraggable: Boolean,
        val parentId: Long,
        val canManageCategoryOfChannel: Boolean,
    ) : CategoricalDragAndDropAdapter.Payload {
        override val type: Int get() = TYPE_CHANNEL
        override val key: String get() = channel.id.toString()
        override val position: Int get() = channel.position
        override val category: String get() = type.toString()
    }

    var onClickListener: ((Long) -> Unit)? = null

    private var onPositionUpdateListener: ((Map<Long, UpdatedPosition>) -> Unit)? = null

    init {
        if (isDraggable) {
            ItemTouchHelper(DragAndDropHelper(this)).attachToRecyclerView(recycler)
        }
    }

    fun setPositionUpdateListener(listener: (Map<Long, UpdatedPosition>) -> Unit) {
        onPositionUpdateListener = listener
    }

    override fun onCreateViewHolder(
        parent: android.view.ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, CategoricalDragAndDropAdapter.Payload> = when (viewType) {
        TYPE_CHANNEL -> ChannelListItem(this)
        TYPE_CATEGORY -> CategoryListItem(this)
        else -> throw invalidViewTypeException(viewType)
    }

    /**
     * A row may only be dropped below a header whose category it is allowed to manage; dropping
     * above the first row (`toPosition == 0`) is never valid because there is no header there.
     */
    override fun isValidMove(fromPosition: Int, toPosition: Int): Boolean {
        if (toPosition <= 0) return false
        return when (val above = getItem(toPosition - 1)) {
            is ChannelItem -> above.canManageCategoryOfChannel
            is CategoryItem -> above.canManageChannelsOfCategory
            else -> false
        }
    }

    override fun computeChangedPositions(): Map<String, Int> {
        val changedPositions = HashMap<Long, Int>()
        val changedParentIds = HashMap<Long, Long>()
        var currentCategoryId: Long? = null

        dataCopy.forEachIndexed { index, payload ->
            val id = payload.idOrNull() ?: 0L
            if (payload.type == TYPE_CATEGORY) currentCategoryId = id.takeIf { it > 0L }
            val parentId = (payload as? ChannelItem)?.parentId?.takeIf { it > 0L }

            if (origPositions[payload.key] != index && payload.canBeManaged()) {
                changedPositions[id] = index
                if (parentId != currentCategoryId && payload.type == TYPE_CHANNEL) {
                    changedParentIds[id] = currentCategoryId ?: NO_CATEGORY
                }
            }
        }

        handleChangedPositions(changedPositions, changedParentIds)
        // Positions are reported through the listener above, not through the base class.
        return emptyMap()
    }

    override fun onNewPositions(newPositions: Map<String, Int>) = Unit

    private fun handleChangedPositions(
        changedPositions: Map<Long, Int>,
        changedParentIds: Map<Long, Long>,
    ) {
        val updates = changedPositions.mapValues { (channelId, position) ->
            UpdatedPosition(position, changedParentIds[channelId])
        }
        onPositionUpdateListener?.invoke(updates)
    }

    private fun CategoricalDragAndDropAdapter.Payload.idOrNull(): Long? = when (this) {
        is ChannelItem -> channel.id
        is CategoryItem -> id
        else -> {
            AppLog.e("Invalid type: $type")
            null
        }
    }

    private fun CategoricalDragAndDropAdapter.Payload.canBeManaged(): Boolean = when (this) {
        is ChannelItem -> canManageCategoryOfChannel
        is CategoryItem -> canManageCategory
        else -> false
    }

    private class CategoryListItem(
        adapter: SettingsChannelListAdapter,
    ) : MGRecyclerViewHolder<SettingsChannelListAdapter, CategoricalDragAndDropAdapter.Payload>(
        R.layout.settings_channel_list_category_item,
        adapter,
    ),
        DragAndDropHelper.DraggableViewHolder {

        private val drag: ImageView =
            itemView.findViewById(R.id.settings_channel_list_category_item_drag)
        private val text: TextView =
            itemView.findViewById(R.id.settings_channel_list_category_item_text)
        private var categoryItem: CategoryItem? = null

        override fun canDrag(): Boolean =
            categoryItem?.let { it.isDraggable && it.canManageCategory } == true

        override fun onDragStateChanged(dragging: Boolean) = Unit

        override fun onConfigure(position: Int, data: CategoricalDragAndDropAdapter.Payload) {
            super.onConfigure(position, data)
            val item = data as? CategoryItem ?: return
            categoryItem = item
            adapter.onClickListener?.let { onClick ->
                itemView.setOnClickListener { onClick(item.id) }
            }
            text.text = item.name ?: itemView.context.getString(R.string.uncategorized)
            drag.visibility = if (canDrag()) View.VISIBLE else View.GONE
        }
    }

    private class ChannelListItem(
        adapter: SettingsChannelListAdapter,
    ) : MGRecyclerViewHolder<SettingsChannelListAdapter, CategoricalDragAndDropAdapter.Payload>(
        R.layout.settings_channel_list_item,
        adapter,
    ),
        DragAndDropHelper.DraggableViewHolder {

        private val drag: ImageView = itemView.findViewById(R.id.settings_channel_list_item_drag)
        private val hash: ImageView = itemView.findViewById(R.id.settings_channel_list_item_hash)
        private val name: TextView = itemView.findViewById(R.id.settings_channel_list_item_name)
        private val selectedOverlay: View =
            itemView.findViewById(R.id.settings_channel_list_item_selected_overlay)
        private var channelItem: ChannelItem? = null

        override fun canDrag(): Boolean =
            channelItem?.let { it.isDraggable && it.canManageCategoryOfChannel } == true

        override fun onDragStateChanged(dragging: Boolean) {
            selectedOverlay.visibility = if (dragging) View.VISIBLE else View.GONE
        }

        override fun onConfigure(position: Int, data: CategoricalDragAndDropAdapter.Payload) {
            super.onConfigure(position, data)
            val item = data as? ChannelItem ?: return
            channelItem = item
            itemView.setOnClickListener(null)
            adapter.onClickListener?.let { onClick ->
                itemView.setOnClickListener { onClick(item.channel.id) }
            }
            hash.setImageResource(iconFor(item.channel.type))
            name.text = item.channel.getDisplayName()
            drag.visibility = if (canDrag()) View.VISIBLE else View.GONE
        }

        private fun iconFor(channelType: Int): Int = when (channelType) {
            Channel.GUILD_VOICE -> R.drawable.ic_channel_voice
            Channel.GUILD_ANNOUNCEMENT -> R.drawable.ic_channel_announcements
            Channel.GUILD_STAGE_VOICE -> R.drawable.ic_channel_stage_24dp
            else -> R.drawable.ic_channel_text
        }
    }

    companion object {
        const val TYPE_CHANNEL = 0
        const val TYPE_CATEGORY = 1

        /** Sent for a channel dragged out of every category. */
        private const val NO_CATEGORY = -1L
    }
}
