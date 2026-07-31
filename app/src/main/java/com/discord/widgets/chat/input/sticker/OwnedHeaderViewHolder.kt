package com.discord.widgets.chat.input.sticker

import android.widget.TextView
import com.discord.R
import com.discord.i18n.i18nFormat
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

class OwnedHeaderViewHolder(
    adapter: WidgetStickerAdapter,
) : MGRecyclerViewHolder<WidgetStickerAdapter, MGRecyclerDataPayload>(
    R.layout.expression_picker_header_item,
    adapter,
) {
    private val headerText = itemView as TextView

    override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
        super.onConfigure(position, data)
        val headerType = (data as? HeaderItem)?.headerType ?: return
        headerText.text = when (headerType) {
            is HeaderType.PackItem -> headerType.pack.name
            is HeaderType.GuildItem -> headerType.guild.name
            HeaderType.Recent -> itemView.context.getString(R.string.sticker_picker_categories_recent)
        }
        headerText.contentDescription = itemView.context.i18nFormat(
            R.string.sticker_category_a11y_label,
            "categoryName" to headerText.text,
        )
    }
}
