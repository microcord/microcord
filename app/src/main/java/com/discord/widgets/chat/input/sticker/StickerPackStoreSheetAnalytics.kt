package com.discord.widgets.chat.input.sticker

import com.discord.api.sticker.Sticker

data class StickerPackStoreSheetAnalytics(
    val sticker: Sticker,
    val type: StickerPackStoreSheetViewType,
    val location: String?,
    val section: String,
)
