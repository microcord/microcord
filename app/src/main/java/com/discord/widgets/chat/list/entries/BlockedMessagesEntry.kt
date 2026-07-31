package com.discord.widgets.chat.list.entries

import com.discord.models.message.Message

data class BlockedMessagesEntry(
    val message: Message,
    val blockedCount: Int,
    private val isExpandedBlock: Boolean,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.BLOCKED_MESSAGES
    override val key: String = ChatListEntry.BLOCKED_MESSAGES.toString()

    override fun isInExpandedBlockedMessageChunk(): Boolean = isExpandedBlock
}
