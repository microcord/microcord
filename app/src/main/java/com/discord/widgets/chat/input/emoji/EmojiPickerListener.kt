package com.discord.widgets.chat.input.emoji

import com.discord.models.domain.emoji.Emoji

/** Receives the emoji the user tapped. Implemented by whichever surface hosts the picker. */
interface EmojiPickerListener {
    fun onEmojiPicked(emoji: Emoji)
}
