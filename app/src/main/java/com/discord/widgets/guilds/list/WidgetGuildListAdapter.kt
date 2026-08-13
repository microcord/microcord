package com.discord.widgets.guilds.list

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.color.ColorCompat
import kotlin.math.abs

/**
 * The guild sidebar's adapter, and the drag controller behind
 * [GuildsDragAndDropCallback.Controller].
 *
 * It owns no state of its own beyond the current item list: every drag gesture is translated into
 * an [Operation] and handed to [InteractionListener], and the resulting list comes back through
 * [setItems]. That is what lets the sidebar preview a folder merge before the finger lifts —
 * the adapter never mutates anything itself.
 */
class WidgetGuildListAdapter(
    private val layoutManager: LinearLayoutManager,
    private val interactionListener: InteractionListener,
) : RecyclerView.Adapter<GuildListViewHolder>(), GuildsDragAndDropCallback.Controller {

    private var items: List<GuildListItem> = emptyList()
    private val boundingBoxRect = Rect()
    private var draggingItem: GuildListViewHolder? = null

    /** Vertical centre of the row being dragged, in recycler coordinates. */
    private var selectedCenterY: Int = 0

    /** Vertical centre of the row [chooseDropTarget] last settled on. */
    private var targetCenterY: Int = 0

    private var bottomNavHeight: Int = 0

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].itemId

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is GuildListItem.FriendsItem -> GuildListItem.TYPE_FRIENDS
        GuildListItem.DividerItem -> GuildListItem.TYPE_DIVIDER
        is GuildListItem.PrivateChannelItem -> GuildListItem.TYPE_DM
        is GuildListItem.GuildItem -> GuildListItem.TYPE_GUILD
        is GuildListItem.UnavailableItem -> GuildListItem.TYPE_UNAVAILABLE
        is GuildListItem.GeoRestrictedItem -> GuildListItem.TYPE_GEO_RESTRICTED
        GuildListItem.CreateItem -> GuildListItem.TYPE_CREATE
        is GuildListItem.FolderItem -> GuildListItem.TYPE_FOLDER
        GuildListItem.HelpItem -> GuildListItem.TYPE_HELP
        GuildListItem.SpaceItem -> GuildListItem.TYPE_BOTTOM_NAV_SPACE
        GuildListItem.DiscoveryItem -> GuildListItem.TYPE_DISCOVERY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GuildListViewHolder {
        fun inflate(layoutRes: Int): View =
            LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)

        return when (viewType) {
            GuildListItem.TYPE_FRIENDS -> {
                val itemView = inflate(R.layout.widget_guilds_list_item_profile)
                GuildListViewHolder.FriendsViewHolder(itemView) {
                    interactionListener.onItemClicked(itemView, it)
                }
            }
            GuildListItem.TYPE_DIVIDER ->
                GuildListViewHolder.SimpleViewHolder(inflate(R.layout.widget_guilds_list_item_divider))
            GuildListItem.TYPE_DM -> {
                val itemView = inflate(R.layout.widget_guilds_list_item_dm_vertical)
                GuildListViewHolder.PrivateChannelViewHolder(
                    itemView,
                    onClicked = { interactionListener.onItemClicked(itemView, it) },
                    onLongPressed = { interactionListener.onItemLongPressed(itemView, it) },
                )
            }
            GuildListItem.TYPE_GUILD -> {
                val itemView = inflate(R.layout.widget_guilds_list_item_guild_vertical)
                GuildListViewHolder.GuildViewHolder(
                    itemView,
                    overlayColor = ColorCompat.getThemedColor(parent.context, R.attr.colorBackgroundTertiary),
                    overlayColorInFolder = ColorCompat.getThemedColor(parent.context, R.attr.primary_600),
                    onClicked = { interactionListener.onItemClicked(itemView, it) },
                    onLongPressed = { interactionListener.onItemLongPressed(itemView, it) },
                )
            }
            GuildListItem.TYPE_UNAVAILABLE ->
                GuildListViewHolder.SimpleViewHolder(inflate(R.layout.widget_guilds_list_item_guild_unavailable))
            GuildListItem.TYPE_GEO_RESTRICTED ->
                GuildListViewHolder.GeoRestrictedViewHolder(
                    inflate(R.layout.widget_guilds_list_item_guild_geo_restricted),
                )
            GuildListItem.TYPE_CREATE ->
                GuildListViewHolder.SimpleViewHolder(inflate(R.layout.widget_guilds_list_item_guild_new))
            GuildListItem.TYPE_FOLDER -> {
                val itemView = inflate(R.layout.widget_guilds_list_item_folder)
                GuildListViewHolder.FolderViewHolder(
                    itemView,
                    onClicked = { interactionListener.onItemClicked(itemView, it) },
                    onLongPressed = { interactionListener.onItemLongPressed(itemView, it) },
                )
            }
            GuildListItem.TYPE_HELP ->
                GuildListViewHolder.SimpleViewHolder(inflate(R.layout.widget_guilds_list_item_guild_nux))
            GuildListItem.TYPE_BOTTOM_NAV_SPACE ->
                GuildListViewHolder.SpaceViewHolder(inflate(R.layout.recycler_item_bottom_nav_space))
            GuildListItem.TYPE_DISCOVERY ->
                GuildListViewHolder.DiscoveryViewHolder(inflate(R.layout.widget_guilds_list_item_discovery))
            else -> error("invalid view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: GuildListViewHolder, position: Int) {
        when (val item = items[position]) {
            is GuildListItem.GuildItem -> (holder as GuildListViewHolder.GuildViewHolder).configure(item)
            is GuildListItem.FriendsItem -> (holder as GuildListViewHolder.FriendsViewHolder).configure(item)
            is GuildListItem.PrivateChannelItem ->
                (holder as GuildListViewHolder.PrivateChannelViewHolder).configure(item)
            is GuildListItem.FolderItem -> (holder as GuildListViewHolder.FolderViewHolder).configure(item)
            // The chrome rows carry no state, so the click listener is all the binding they need.
            is GuildListItem.GeoRestrictedItem -> {
                (holder as GuildListViewHolder.GeoRestrictedViewHolder).configure(item)
                holder.itemView.setOnClickListener { interactionListener.onItemClicked(holder.itemView, item) }
            }
            is GuildListItem.UnavailableItem, GuildListItem.CreateItem, GuildListItem.HelpItem,
            GuildListItem.DiscoveryItem,
            ->
                holder.itemView.setOnClickListener { interactionListener.onItemClicked(holder.itemView, item) }
            GuildListItem.SpaceItem -> (holder as GuildListViewHolder.SpaceViewHolder).configure(bottomNavHeight)
            GuildListItem.DividerItem -> Unit
        }
    }

    /**
     * Replaces the list.
     *
     * [calculateDiff] is false immediately after a drag: the view state the drag produced is
     * already on screen, and re-animating it would fight the item touch helper.
     */
    fun setItems(newItems: List<GuildListItem>, calculateDiff: Boolean) {
        if (calculateDiff) {
            val diff = DiffUtil.calculateDiff(createDiffUtilCallback(items, newItems), true)
            items = newItems
            diff.dispatchUpdatesTo(this)
        } else {
            items = newItems
            notifyDataSetChanged()
        }
    }

    /** Resizes the trailing spacer so the last guild is never hidden behind the tab bar. */
    fun handleBottomNavHeight(height: Int) {
        bottomNavHeight = height
        notifyItemChanged(items.lastIndexOf(GuildListItem.SpaceItem))
    }

    // region drag controller

    /**
     * Picks the candidate whose centre is closest to the dragged row's centre.
     *
     * The default implementation maximises overlap area, which in a one-icon-wide list keeps
     * choosing the row the finger already left. Both centres are remembered because [onMove] needs
     * them to decide between "above", "below" and "onto".
     */
    override fun chooseDropTarget(
        selected: RecyclerView.ViewHolder,
        dropTargets: MutableList<RecyclerView.ViewHolder>,
        curX: Int,
        curY: Int,
    ): RecyclerView.ViewHolder? {
        layoutManager.getTransformedBoundingBox(selected.itemView, false, boundingBoxRect)
        selectedCenterY = boundingBoxRect.centerY()

        var best: RecyclerView.ViewHolder? = null
        var bestDistance = Int.MAX_VALUE
        for (candidate in dropTargets) {
            layoutManager.getTransformedBoundingBox(candidate.itemView, false, boundingBoxRect)
            val candidateCenterY = boundingBoxRect.centerY()
            val distance = abs(selectedCenterY - candidateCenterY)
            if (distance < bestDistance) {
                targetCenterY = candidateCenterY
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    override fun onDragStarted(viewHolder: RecyclerView.ViewHolder) {
        val holder = viewHolder as GuildListViewHolder
        holder.onDragStarted()
        draggingItem = holder
    }

    override fun onDrop() {
        val wasMerge = interactionListener.onDrop()
        draggingItem?.onDragEnded(wasMerge)
        draggingItem = null
    }

    override fun onMove(
        recyclerView: RecyclerView,
        source: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean {
        interactionListener.onItemMoved()

        val sourceId = (source as GuildListViewHolder).itemId
        val targetId = (target as GuildListViewHolder).itemId
        var fromPosition = -1
        var toPosition = -1
        items.forEachIndexed { index, item ->
            when (item.itemId) {
                sourceId -> fromPosition = index
                targetId -> toPosition = index
            }
        }
        if (fromPosition == -1 || toPosition == -1) return false

        val sourceItem = items[fromPosition]
        val targetItem = items[toPosition]
        val targetPosition = resolveTargetPosition(source.itemView.height)
        if (!isValidMove(sourceItem, targetItem, targetPosition)) return false

        interactionListener.onOperation(
            when (targetPosition) {
                TargetPosition.TOP -> Operation.MoveAbove(fromPosition, toPosition)
                TargetPosition.CENTER -> Operation.TargetOperation(fromPosition, toPosition)
                TargetPosition.BOTTOM -> Operation.MoveBelow(fromPosition, toPosition)
            },
        )
        return true
    }

    /**
     * Which third of the target row the drag is over: the middle 20 % combines the two rows, the
     * rest reorders around it.
     */
    private fun resolveTargetPosition(sourceHeight: Int): TargetPosition = when {
        abs(selectedCenterY - targetCenterY) < sourceHeight * NEAR_CENTER_PERCENTAGE -> TargetPosition.CENTER
        selectedCenterY < targetCenterY -> TargetPosition.TOP
        else -> TargetPosition.BOTTOM
    }

    private fun isValidMove(
        sourceItem: GuildListItem,
        targetItem: GuildListItem,
        targetPosition: TargetPosition,
    ): Boolean {
        if (isPendingGuildOperation(sourceItem, targetItem)) return false
        return when (sourceItem) {
            is GuildListItem.GuildItem -> when (targetItem) {
                // Dropping onto an already-open folder would be ambiguous: its children are
                // right there, so the guild goes between them instead.
                is GuildListItem.FolderItem ->
                    !(targetItem.isOpen && targetPosition == TargetPosition.CENTER)
                // A lurked guild is not really joined, so it can neither move nor be merged into.
                // A guild already inside a folder can be reordered past but not merged with.
                is GuildListItem.GuildItem -> !targetItem.isLurkingGuild &&
                    (targetItem.folderId == null || targetPosition != TargetPosition.CENTER)
                // The "+" row is the end of the list; only the slot above it is real.
                GuildListItem.CreateItem -> targetPosition == TargetPosition.TOP
                else -> false
            }
            is GuildListItem.FolderItem -> when (targetItem) {
                is GuildListItem.GuildItem -> !targetItem.isLurkingGuild &&
                    targetItem.folderId == null &&
                    targetPosition != TargetPosition.CENTER
                // Folders never nest, so only the slots around another folder are valid — and
                // below an open folder means "inside it", which is not a folder move.
                is GuildListItem.FolderItem -> targetPosition == TargetPosition.TOP ||
                    (!targetItem.isOpen && targetPosition == TargetPosition.BOTTOM)
                else -> false
            }
            else -> error("trying to drag invalid source")
        }
    }

    /** Guilds still awaiting membership screening are pinned; nothing may move them or past them. */
    private fun isPendingGuildOperation(sourceItem: GuildListItem, targetItem: GuildListItem): Boolean =
        isPendingGuildItem(sourceItem) || isPendingGuildItem(targetItem)

    private fun isPendingGuildItem(item: GuildListItem): Boolean = when (item) {
        is GuildListItem.GuildItem -> item.isPendingGuild
        is GuildListItem.FolderItem -> item.isPendingGuildsFolder
        else -> false
    }

    // endregion

    private fun createDiffUtilCallback(
        oldItems: List<GuildListItem>,
        newItems: List<GuildListItem>,
    ): DiffUtil.Callback = object : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldItems.size
        override fun getNewListSize(): Int = newItems.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldItems[oldItemPosition].itemId == newItems[newItemPosition].itemId
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldItems[oldItemPosition] == newItems[newItemPosition]
    }

    /** Where a drag currently sits relative to the row it is over. */
    private enum class TargetPosition { TOP, CENTER, BOTTOM }

    /** What a completed drag gesture asks the view model to do. */
    sealed class Operation {
        data class MoveAbove(val fromPosition: Int, val targetPosition: Int) : Operation()
        data class MoveBelow(val fromPosition: Int, val targetPosition: Int) : Operation()

        /** Merge the two rows: guild + guild creates a folder, guild + folder joins it. */
        data class TargetOperation(val fromPosition: Int, val targetPosition: Int) : Operation()
    }

    interface InteractionListener {
        fun onItemClicked(view: View, item: GuildListItem)
        fun onItemLongPressed(view: View, item: GuildListItem)
        fun onItemMoved()
        fun onOperation(operation: Operation)

        /** Returns true when the drop merged the dragged row away, so it should not snap back. */
        fun onDrop(): Boolean
    }

    private companion object {
        /** Fraction of a row's height around its centre that counts as "onto" rather than "past". */
        private const val NEAR_CENTER_PERCENTAGE = 0.2f
    }
}
