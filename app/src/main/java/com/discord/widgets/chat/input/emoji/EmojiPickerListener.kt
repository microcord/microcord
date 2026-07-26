package com.discord.widgets.chat.input.emoji

import com.discord.models.domain.emoji.Emoji

/** Receives the emoji the user tapped. Implemented by whichever surface hosts the picker. */
interface EmojiPickerListener {
    fun onEmojiPicked(emoji: Emoji)
}

/**
 * Row-level callback inside the picker's own grid.
 *
 * Separate from [EmojiPickerListener] because the adapter must not know which surface it is in:
 * the picker translates a selection into whatever its host needs (chat text, a reaction, a
 * sticker-sheet dismissal).
 */
interface OnEmojiSelectedListener {
    fun onEmojiSelected(emoji: Emoji)
}
