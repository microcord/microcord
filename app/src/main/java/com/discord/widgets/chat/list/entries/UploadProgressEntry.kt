package com.discord.widgets.chat.list.entries

data class UploadProgressEntry(
    val messageNonce: String,
    val channelId: Long,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.UPLOAD_STATUS
    override val key: String = "${ChatListEntry.UPLOAD_STATUS} $messageNonce"
}
