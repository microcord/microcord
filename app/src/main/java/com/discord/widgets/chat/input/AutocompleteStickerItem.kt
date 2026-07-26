package com.discord.widgets.chat.input

import com.discord.api.sticker.Sticker
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

/** One sticker suggestion in the row above the autocomplete list. */
data class AutocompleteStickerItem(
    val sticker: Sticker,
    override val key: String = sticker.id.toString(),
    override val type: Int = 0,
) : MGRecyclerDataPayload
