package com.discord.widgets.chat.input.sticker

import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

data class HeaderItem(val headerType: HeaderType) : MGRecyclerDataPayload {
    override val type: Int = WidgetStickerAdapter.ITEM_TYPE_OWNED_HEADER
    override val key: String = headerType.getId()
}
