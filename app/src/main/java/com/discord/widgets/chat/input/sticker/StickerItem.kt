package com.discord.widgets.chat.input.sticker

import com.discord.api.sticker.Sticker
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.widgets.stickers.StickerSendability

data class StickerItem(
    val sticker: Sticker,
    val stickerAnimationSettings: Int,
    val mode: Mode,
    val sendability: StickerSendability,
) : MGRecyclerDataPayload {
    override val type: Int = WidgetStickerAdapter.ITEM_TYPE_STICKER
    override val key: String = "sticker:${sticker.id}"

    enum class Mode { OWNED, STORE }
}
