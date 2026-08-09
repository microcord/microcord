package com.discord.widgets.chat.list.entries

import com.discord.models.message.Message
data class UploadProgressEntry(
    val messageNonce: String,
    val channelId: Long,
    val message: Message,
) : ChatListEntry() {

    override val highlightMessage: Message get() = message
    override val type: Int = ChatListEntry.UPLOAD_STATUS
    override val key: String = "${ChatListEntry.UPLOAD_STATUS} $messageNonce"
}
