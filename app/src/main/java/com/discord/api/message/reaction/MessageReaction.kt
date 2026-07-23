package com.discord.api.message.reaction

data class MessageReaction(
    val count: Int,
    val emoji: MessageReactionEmoji,
    val me: Boolean,
)

data class MessageReactionEmoji(
    val id: String?,
    val name: String?,
    val animated: Boolean,
) {
    val reactionKey: String
        get() = id ?: requireNotNull(name)

    val isCustom: Boolean
        get() = id != null
}
