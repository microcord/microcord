package com.discord.widgets.forums

import com.discord.api.channel.ForumTag
import com.discord.api.message.reaction.MessageReactionEmoji

data class PostTagData(
    val messageReactionEmoji: MessageReactionEmoji,
    val tagName: String,
) {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun fromTag(tag: ForumTag, animated: Boolean = false) = PostTagData(
            MessageReactionEmoji(tag.customEmojiId, tag.emojiName, animated),
            tag.name,
        )
    }
}
