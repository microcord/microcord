package com.discord.api.message.reaction

/**
 * Reactions the gateway coalesced instead of sending one event each.
 *
 * A burst on a popular message is one dispatch rather than hundreds; it carries no counts, so it
 * only makes sense expanded back into the individual additions the stores already know how to
 * apply.
 */
data class MessageReactionAddMany(
    val channelId: Long,
    val messageId: Long,
    val guildId: Long? = null,
    val reactions: List<DebouncedReaction> = emptyList(),
)

data class DebouncedReaction(
    val users: List<Long> = emptyList(),
    val emoji: MessageReactionEmoji,
)
