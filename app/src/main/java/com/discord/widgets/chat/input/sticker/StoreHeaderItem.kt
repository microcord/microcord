package com.discord.widgets.chat.input.sticker

import com.discord.models.sticker.dto.ModelStickerPack
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

data class StoreHeaderItem(
    val pack: ModelStickerPack,
    val isNewPack: Boolean,
) : MGRecyclerDataPayload {
    override val type: Int = WidgetStickerAdapter.ITEM_TYPE_STORE_HEADER
    override val key: String = "store-header:${pack.id}"
}
