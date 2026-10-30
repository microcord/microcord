package com.discord.widgets.chat.list.adapter

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.discord.R
import com.discord.databinding.WidgetChatListAdapterItemMediaMosaicBinding
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.widgets.botuikit.views.v2.MediaGalleryLayout
import com.discord.widgets.chat.list.ChatAttachmentView
import com.discord.widgets.chat.list.FragmentLifecycleListener
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MediaMosaicEntry

/**
 * A message's images and videos as one grid, like modern Discord's media mosaic. The cells come from
 * [MediaGalleryLayout], which is the same mosaic Discord uses here; each tile is an ordinary
 * [ChatAttachmentView], so spoilers, GIFs, video and the media viewer behave as for a lone image.
 */
class WidgetChatListAdapterItemMediaMosaic(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_media_mosaic, adapter),
    FragmentLifecycleListener {

    private val binding = WidgetChatListAdapterItemMediaMosaicBinding.bind(itemView)
    private val grid = binding.chatListItemMediaMosaic
    private val density = itemView.resources.displayMetrics.density
    private val tiles = ArrayList<ChatAttachmentView>()

    init {
        grid.clipToOutline = true
        grid.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, CORNER_RADIUS_DP * density)
        }
    }

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as MediaMosaicEntry
        configureCellHighlight(
            entry.message,
            binding.chatListAdapterItemHighlightedBg,
            binding.chatListAdapterItemGutterBg,
            entry.isPendingReply,
        )
        val width = minOf(
            EmbedResourceUtils.computeMaximumImageWidthPx(itemView.context),
            (MAX_WIDTH_DP * density).toInt(),
        )
        val layout = MediaGalleryLayout.layout(
            count = entry.items.size,
            width = width,
            gap = (MediaGalleryLayout.GAP_DP * density).toInt(),
            singleMaxHeight = (MediaGalleryLayout.SINGLE_MAX_HEIGHT_DP * density).toInt(),
        )
        grid.layoutParams = grid.layoutParams.apply {
            this.width = width
            height = layout.height
        }
        // Spare tiles are hidden, not dropped: a recycled row with more images reuses them instead
        // of inflating a fresh attachment view per tile.
        while (tiles.size < entry.items.size) tiles += ChatAttachmentView(itemView.context).also(grid::addView)
        tiles.forEachIndexed { index, tile ->
            val used = index < entry.items.size
            if (!used && tile.visibility == View.VISIBLE) tile.onPause()
            tile.visibility = if (used) View.VISIBLE else View.GONE
        }

        entry.items.forEachIndexed { index, item ->
            val cell = layout.cells[index]
            val tile = tiles[index]
            tile.layoutParams = FrameLayout.LayoutParams(cell.width, cell.height).apply {
                leftMargin = cell.left
                topMargin = cell.top
            }
            tile.setTileSize(cell.width, cell.height)
            tile.configure(
                entry = item,
                isSpoilerClickAllowed = adapter.data.isSpoilerClickAllowed,
                onSpoilerBlocked = { adapter.eventHandler.onMessageClicked(item.message, item.isThreadStarterMessage) },
                onQuickDownload = adapter.eventHandler::onQuickDownloadClicked,
            )
        }
    }

    override fun onPause() = tiles.forEach(ChatAttachmentView::onPause)

    override fun onResume() = tiles.filter { it.visibility == View.VISIBLE }.forEach(ChatAttachmentView::onResume)

    private companion object {
        /** Discord caps the mosaic's width at this. */
        const val MAX_WIDTH_DP = 550
        const val CORNER_RADIUS_DP = 8f
    }
}
