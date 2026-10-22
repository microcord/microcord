package com.discord.widgets.chat.list.entries

import com.discord.models.message.Message

/** A message's images and videos shown together as one grid, each tile one of [items]. */
data class MediaMosaicEntry(
    val items: List<AttachmentEntry>,
    override val isPendingReply: Boolean = false,
) : ChatListEntry() {
    val message: Message get() = items.first().message

    override val highlightMessage: Message get() = message
    override val type: Int = ChatListEntry.MEDIA_MOSAIC
    override val key: String = "${ChatListEntry.MEDIA_MOSAIC}${message.id}"

    override fun isInExpandedBlockedMessageChunk(): Boolean = items.first().isBlockedExpanded
}
