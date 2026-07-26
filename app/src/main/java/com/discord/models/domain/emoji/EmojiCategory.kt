package com.discord.models.domain.emoji

/**
 * The picker's top-level tabs, in display order.
 *
 * [containsOnlyUnicode] is false for the three synthetic tabs at the front, which mix guild uploads
 * with Unicode glyphs.
 */
enum class EmojiCategory(val stringRep: String, val containsOnlyUnicode: Boolean) {
    FAVORITE("favorite", false),
    RECENT("recent", false),
    CUSTOM("custom", false),
    PEOPLE("people", true),
    NATURE("nature", true),
    FOOD("food", true),
    ACTIVITY("activity", true),
    TRAVEL("travel", true),
    OBJECTS("objects", true),
    SYMBOLS("symbols", true),
    FLAGS("flags", true);

    companion object {
        @JvmStatic
        fun getByString(value: String?): EmojiCategory? = entries.firstOrNull { it.stringRep == value }
    }
}
