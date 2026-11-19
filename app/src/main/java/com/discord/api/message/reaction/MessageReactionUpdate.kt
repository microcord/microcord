package com.discord.api.message.reaction

data class MessageReactionUpdate(
    val userId: Long,
    val channelId: Long,
    val messageId: Long,
    val emoji: MessageReactionEmoji,
    /** [MessageReaction.TYPE_NORMAL] or [MessageReaction.TYPE_BURST]; absent means normal. */
    val type: Int = MessageReaction.TYPE_NORMAL,
    val burstColors: List<String>? = null,
) {
    val isBurst: Boolean get() = type == MessageReaction.TYPE_BURST
}

data class MessageReactionRemoveAll(val channelId: Long, val messageId: Long)
data class MessageReactionRemoveEmoji(val channelId: Long, val messageId: Long, val emoji: MessageReactionEmoji)
