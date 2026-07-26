package com.discord.widgets.chat.input.emoji

import java.io.Serializable

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
}

/**
 * Which emoji the picker is allowed to offer.
 *
 * [Guild] restricts the picker to one guild's uploads — used from guild settings — while [Chat]
 * offers everything usable in the selected channel and [Global] everything the user owns.
 */
sealed class EmojiPickerContextType : Serializable {
    object Chat : EmojiPickerContextType()
    object Global : EmojiPickerContextType()
    data class Guild(val guildId: Long) : EmojiPickerContextType()
}
