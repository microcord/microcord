package com.discord.widgets.chat.input.sticker

import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.widgets.stickers.setStickerCountText

class StoreHeaderViewHolder(
    adapter: WidgetStickerAdapter,
    private val onStickerHeaderItemClicked: ((StoreHeaderItem) -> Unit)?,
) : MGRecyclerViewHolder<WidgetStickerAdapter, MGRecyclerDataPayload>(
    R.layout.sticker_store_header_item,
    adapter,
) {
    private val title: TextView = itemView.findViewById(R.id.sticker_store_header_item_title)
    private val subtitle: TextView = itemView.findViewById(R.id.sticker_store_header_item_subtitle)
    private val description: TextView = itemView.findViewById(R.id.sticker_store_header_item_description)
    private val animatedBadge: View = itemView.findViewById(R.id.sticker_store_header_item_animated)
    private val premiumBadge: View = itemView.findViewById(R.id.sticker_store_header_item_premium)
    private val container: View = itemView.findViewById(R.id.sticker_store_header_item_container)

    override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
        super.onConfigure(position, data)
        val item = data as? StoreHeaderItem ?: return
        title.text = item.pack.name
        subtitle.setStickerCountText(item.pack.stickers.size)
        animatedBadge.visibility = if (item.pack.isAnimatedPack()) View.VISIBLE else View.GONE
        premiumBadge.visibility = if (item.pack.isPremiumPack()) View.VISIBLE else View.GONE
        if (adapter.showStickerPackDescriptions) {
            description.setTextAndVisibilityBy(item.pack.description)
        } else {
            description.visibility = View.GONE
        }
        val hasDetails = item.pack.isAnimatedPack() || item.pack.isPremiumPack() ||
            item.pack.isLimitedPack()
        container.setOnClickListener(
            if (hasDetails) View.OnClickListener { onStickerHeaderItemClicked?.invoke(item) } else null,
        )
    }
}
