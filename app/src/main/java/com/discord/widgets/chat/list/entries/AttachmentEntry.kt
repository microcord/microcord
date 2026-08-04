package com.discord.widgets.chat.list.entries

import com.discord.api.message.attachment.MessageAttachment
import com.discord.models.message.Message
import com.discord.stores.StoreMessageState

/** One uploaded file attached to a message. */
data class AttachmentEntry(
    val embedIndex: Int,
    val guildId: Long,
    val message: Message,
    val messageState: StoreMessageState.State?,
    val attachment: MessageAttachment,
    val isBlockedExpanded: Boolean,
    val allowAnimatedEmojis: Boolean,
    val autoPlayGifs: Boolean,
    val isThreadStarterMessage: Boolean,
    override val isPendingReply: Boolean = false,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.MESSAGE_ATTACHMENT
    override val key: String = "${ChatListEntry.MESSAGE_ATTACHMENT}${message.id}"

    override fun isInExpandedBlockedMessageChunk(): Boolean = isBlockedExpanded
}
