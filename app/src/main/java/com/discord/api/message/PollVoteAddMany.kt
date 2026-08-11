package com.discord.api.message

/**
 * Poll votes the gateway coalesced instead of sending one event each.
 *
 * Only additions are batched; a retracted vote still arrives as its own event.
 */
data class PollVoteAddMany(
    val channelId: Long,
    val messageId: Long,
    val guildId: Long? = null,
    val votes: List<PollVoteBatchEntry> = emptyList(),
)

data class PollVoteBatchEntry(
    val answerId: Int,
    val users: List<Long> = emptyList(),
)
