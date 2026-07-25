package com.discord.api.message.reaction

data class MessageReactionUpdate(
    val userId: Long,
    val channelId: Long,
    val messageId: Long,
    val emoji: MessageReactionEmoji,
)

data class MessageReactionRemoveAll(val channelId: Long, val messageId: Long)
data class MessageReactionRemoveEmoji(val channelId: Long, val messageId: Long, val emoji: MessageReactionEmoji)
