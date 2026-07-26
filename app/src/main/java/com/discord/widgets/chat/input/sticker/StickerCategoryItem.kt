package com.discord.widgets.chat.input.sticker

import com.discord.api.sticker.Sticker
import com.discord.models.guild.Guild
import com.discord.models.sticker.dto.ModelStickerPack
import com.discord.utilities.recycler.DiffKeyProvider

/**
 * One tab in the picker's bottom rail.
 *
 * [categoryRange] is the half-open `[first, second)` span of positions the tab owns in the sticker
 * grid. It works in both directions: tapping the tab scrolls the grid to `first`, and scrolling the
 * grid selects whichever tab's range contains the first visible position.
 */
sealed class StickerCategoryItem(
    open val isSelected: Boolean,
    open val categoryRange: Pair<Int, Int>,
    val categoryId: Long,
) : DiffKeyProvider {

    data class RecentItem(
        override val isSelected: Boolean,
        override val categoryRange: Pair<Int, Int>,
    ) : StickerCategoryItem(isSelected, categoryRange, RECENT_CATEGORY_ID) {
        override val key: String = "recent"
    }

    data class PackItem(
        val pack: ModelStickerPack,
        override val categoryRange: Pair<Int, Int>,
        override val isSelected: Boolean,
    ) : StickerCategoryItem(isSelected, categoryRange, pack.id) {
        override val key: String = pack.id.toString()
    }

    data class GuildItem(
        val guild: Guild,
        val stickers: List<Sticker>,
        override val categoryRange: Pair<Int, Int>,
        override val isSelected: Boolean,
    ) : StickerCategoryItem(isSelected, categoryRange, guild.id) {
        override val key: String = guild.id.toString()
    }

    companion object {
        /** The sentinel id of the "Frequently used" tab. */
        const val RECENT_CATEGORY_ID = -1L

        const val TYPE_RECENT = 0
        const val TYPE_PACK = 1
        const val TYPE_GUILD = 2
    }
}
