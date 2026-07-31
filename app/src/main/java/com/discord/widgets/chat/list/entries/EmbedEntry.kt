package com.discord.widgets.chat.list.entries

import com.discord.api.message.embed.MessageEmbed
import com.discord.models.message.Message
import com.discord.stores.StoreMessageState

/** One rich embed attached to a message. */
data class EmbedEntry(
    val embedIndex: Int,
    val guildId: Long,
    val message: Message,
    val messageState: StoreMessageState.State?,
    val embed: MessageEmbed,
    val isBlockedExpanded: Boolean,
    val allowAnimatedEmojis: Boolean,
    val autoPlayGifs: Boolean,
    val isThreadStarterMessage: Boolean,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.MESSAGE_EMBED
    override val key: String = "${ChatListEntry.MESSAGE_EMBED}${message.id}"

    override fun isInExpandedBlockedMessageChunk(): Boolean = isBlockedExpanded
}
