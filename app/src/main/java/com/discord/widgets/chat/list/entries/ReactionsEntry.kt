package com.discord.widgets.chat.list.entries

import com.discord.models.message.Message

data class ReactionsEntry(
    val message: Message,
    val canAddReactions: Boolean,
    val canCreateReactions: Boolean,
    val animateEmojis: Boolean,
    override val isPendingReply: Boolean = false,
) : ChatListEntry() {

    override val highlightMessage: Message get() = message
    override val type: Int = ChatListEntry.REACTIONS
    override val key: String = "${ChatListEntry.REACTIONS}${message.nonce ?: message.id}"
}
