package com.discord.widgets.chat.list.entries

data class NewMessagesEntry(val channelId: Long, val messageId: Long) : ChatListEntry() {
    override val type: Int = ChatListEntry.NEW_MESSAGES
    override val key: String = "${ChatListEntry.NEW_MESSAGES}$channelId"
}
