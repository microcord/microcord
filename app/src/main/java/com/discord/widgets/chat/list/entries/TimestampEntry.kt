package com.discord.widgets.chat.list.entries

data class TimestampEntry(val messageId: Long, val timestamp: Long) : ChatListEntry() {
    override val type: Int = ChatListEntry.TIMESTAMP
    override val key: String = "${ChatListEntry.TIMESTAMP}$messageId"
}
