package com.discord.widgets.chat.input.emoji

import com.discord.models.domain.emoji.EmojiCategory
import com.discord.models.guild.Guild

/**
 * One icon in the category rail along the bottom of the picker.
 *
 * [categoryRange] is the half-open span of *grid* positions this category owns. It is what couples
 * the rail to the grid in both directions: tapping an icon scrolls the grid to `first`, and
 * scrolling the grid re-selects whichever icon's range contains the topmost visible position. Ranges
 * are recomputed whenever the item list changes, so they can never point past the end of it.
 */
sealed class EmojiCategoryItem {

    abstract val stableId: Long
    abstract val categoryRange: IntRange
    abstract val isSelected: Boolean

    /** A built-in category: favourites, frequently used, custom, or one of the Unicode groups. */
    data class StandardItem(
        val emojiCategory: EmojiCategory,
        override val categoryRange: IntRange,
        override val isSelected: Boolean,
    ) : EmojiCategoryItem() {
        override val stableId: Long = mapEmojiCategoryToItemId(emojiCategory)
    }

    /** One guild's uploaded emoji, shown with the guild's own icon. */
    data class GuildItem(
        val guild: Guild,
        override val categoryRange: IntRange,
        override val isSelected: Boolean,
    ) : EmojiCategoryItem() {
        override val stableId: Long = mapGuildToItemId(guild)
    }

    /**
     * True only for the pure-Unicode groups. The picker uses this for the "jump to Unicode emoji"
     * shortcut button, which must skip the three synthetic groups at the front of the rail.
     */
    fun containsOnlyUnicodeEmoji(): Boolean =
        this is StandardItem && emojiCategory.containsOnlyUnicode

    companion object {
        const val TYPE_STANDARD = 0
        const val TYPE_GUILD = 1

        /**
         * Ids come from the enum *name*, not the ordinal, so inserting a category in a later
         * version does not renumber every icon in a saved adapter state.
         */
        fun mapEmojiCategoryToItemId(emojiCategory: EmojiCategory): Long =
            emojiCategory.name.hashCode().toLong()

        fun mapGuildToItemId(guild: Guild): Long = guild.id
    }
}
