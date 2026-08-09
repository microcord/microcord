package com.discord.widgets.chat.list.entries

import com.discord.models.message.Message

/**
 * A collapsed run of messages hidden from the viewer.
 *
 * Blocking and ignoring collapse the same way but never share a run: the reference keeps them as
 * separate row types so the label can say which it is.
 */
data class BlockedMessagesEntry(
    val message: Message,
    val blockedCount: Int,
    private val isExpandedBlock: Boolean,
    val isIgnored: Boolean = false,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.BLOCKED_MESSAGES

    // Keyed on the run's first message so two runs in one channel survive diffing.
    override val key: String = "${ChatListEntry.BLOCKED_MESSAGES}${message.id}"

    override fun isInExpandedBlockedMessageChunk(): Boolean = isExpandedBlock
}
