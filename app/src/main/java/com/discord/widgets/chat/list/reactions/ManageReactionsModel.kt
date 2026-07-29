package com.discord.widgets.chat.list.reactions

import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.StoreMessageReactions
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

data class ManageReactionsModel(
    val reactionItems: List<ReactionEmojiItem>,
    val userItems: List<ResultItem>,
)

data class ReactionEmojiItem(
    val reaction: MessageReaction,
    val isSelected: Boolean,
) : MGRecyclerDataPayload {
    override val key = reaction.emoji.reactionKey
    override val type = 0
}

sealed class ResultItem(final override val type: Int) : MGRecyclerDataPayload {
    data class ReactionUser(
        val user: User,
        val channelId: Long,
        val messageId: Long,
        val emoji: MessageReactionEmoji,
        val canDelete: Boolean,
        val guildMember: GuildMember?,
    ) : ResultItem(TYPE_USER) {
        override val key = user.id.toString()
    }

    object Loading : ResultItem(TYPE_LOADING) {
        override val key = type.toString()
    }

    data class Error(
        val channelId: Long,
        val messageId: Long,
        val emoji: MessageReactionEmoji,
    ) : ResultItem(TYPE_ERROR) {
        override val key = type.toString()
    }

    companion object {
        const val TYPE_USER = 0
        const val TYPE_LOADING = 1
        const val TYPE_ERROR = 2

        internal fun from(
            results: StoreMessageReactions.EmojiResults,
            canManageMessages: Boolean,
            myId: Long,
            guildMembers: Map<Long, GuildMember>,
        ): List<ResultItem> = when (results) {
            StoreMessageReactions.EmojiResults.Loading -> listOf(Loading)
            is StoreMessageReactions.EmojiResults.Failure -> listOf(
                Error(results.channelId, results.messageId, results.emoji),
            )
            is StoreMessageReactions.EmojiResults.Users -> results.users.values.map { user ->
                ReactionUser(
                    user,
                    results.channelId,
                    results.messageId,
                    results.emoji,
                    canManageMessages || user.id == myId,
                    guildMembers[user.id],
                )
            }
        }
    }
}
