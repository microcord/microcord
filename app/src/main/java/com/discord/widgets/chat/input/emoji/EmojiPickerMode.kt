package com.discord.widgets.chat.input.emoji

/**
 * Where the picker is being shown.
 *
 * Declaration order matches the reference's `$VALUES` array (`INLINE`, `BOTTOM_SHEET`) rather than
 * the alphabetical order a decompiler prints, because the ordinal is what the reference persists.
 */
enum class EmojiPickerMode {
    /** Docked under the composer, sharing the screen with the chat list. */
    INLINE,

    /** Presented over the current screen, e.g. when picking a reaction. */
    BOTTOM_SHEET,
    ;

    val showsSearchBar: Boolean get() = this == BOTTOM_SHEET
}
