package com.discord.widgets.chat.list.actions

import com.discord.models.domain.emoji.Emoji
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

sealed class EmojiItem(open override val type: Int) : MGRecyclerDataPayload {
    data class EmojiData(val emoji: Emoji) : EmojiItem(TYPE_EMOJI) {
        override val key = "${type}_${emoji.getUniqueId()}"
    }

    object MoreEmoji : EmojiItem(TYPE_MORE_EMOJIS) {
        override val key = type.toString()
    }

    companion object {
        const val TYPE_EMOJI = 0
        const val TYPE_MORE_EMOJIS = 1
    }
}
