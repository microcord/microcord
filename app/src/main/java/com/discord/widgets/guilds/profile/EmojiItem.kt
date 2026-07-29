package com.discord.widgets.guilds.profile

import com.discord.models.domain.emoji.Emoji

sealed class EmojiItem(val type: Int) {
    data class EmojiData(val emoji: Emoji) : EmojiItem(TYPE_EMOJI)
    data class MoreEmoji(val extraEmojiCount: Int) : EmojiItem(TYPE_MORE_EMOJI)

    companion object {
        const val TYPE_EMOJI = 0
        const val TYPE_MORE_EMOJI = 1
    }
}
