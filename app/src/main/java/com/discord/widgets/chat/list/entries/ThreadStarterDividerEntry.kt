package com.discord.widgets.chat.list.entries

data class ThreadStarterDividerEntry(val channelId: Long, val messageId: Long) : ChatListEntry() {
    override val type: Int = ChatListEntry.THREAD_STARTER_DIVIDER
    override val key: String = "${ChatListEntry.THREAD_STARTER_DIVIDER}$channelId"
}
