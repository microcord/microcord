package com.discord.widgets.chat.input.sticker

import com.discord.api.sticker.Sticker
import com.discord.models.guild.Guild
import com.discord.models.sticker.dto.ModelStickerPack
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.widgets.stickers.StickerSendability

/**
 * The rows of the sticker picker's grid: section headers interleaved with the stickers under them.
 *
 * The view types are the adapter's span-size key as well as its holder key — headers take the full
 * row width, stickers take one column — so they live on [WidgetStickerAdapter].
 */

/** One sticker tile. */
data class StickerItem(
    val sticker: Sticker,
    val stickerAnimationSettings: Int,
    val mode: Mode,
    val sendability: StickerSendability,
) : MGRecyclerDataPayload {
    override val type: Int = WidgetStickerAdapter.ITEM_TYPE_STICKER
    override val key: String = "sticker:${sticker.id}"

    /** `STORE` tiles come from the pack-store sheet, where a tap previews instead of sending. */
    enum class Mode { OWNED, STORE }
}

/** What a section header names. */
sealed class HeaderType {
    abstract fun getId(): String

    data class PackItem(val pack: ModelStickerPack) : HeaderType() {
        override fun getId(): String = "owned-header:${pack.id}"
    }

    data class GuildItem(val guild: Guild) : HeaderType() {
        // No prefix, matching the reference: the category rail keys guilds by bare id too.
        override fun getId(): String = guild.id.toString()
    }

    object Recent : HeaderType() {
        override fun getId(): String = "recents"
    }
}

/** A plain section header inside the owned-sticker grid. */
data class HeaderItem(val headerType: HeaderType) : MGRecyclerDataPayload {
    override val type: Int = WidgetStickerAdapter.ITEM_TYPE_OWNED_HEADER
    override val key: String = headerType.getId()
}

/** The richer header used by the pack-store sheet: name, count, and the animated/Nitro badges. */
data class StoreHeaderItem(
    val pack: ModelStickerPack,
    val isNewPack: Boolean,
) : MGRecyclerDataPayload {
    override val type: Int = WidgetStickerAdapter.ITEM_TYPE_STORE_HEADER
    override val key: String = "store-header:${pack.id}"
}
