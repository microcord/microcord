package com.discord.widgets.chat.input.emoji

import com.discord.models.domain.emoji.Emoji

interface OnEmojiSelectedListener {
    fun onEmojiSelected(emoji: Emoji)
    fun onEmojiLongPressed(emoji: Emoji) = Unit
}
