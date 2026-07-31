package com.discord.widgets.chat.list.entries

data class SpacerEntry(private val channelId: Long) : ChatListEntry() {
    override val type: Int = ChatListEntry.SPACER
    override val key: String = "${ChatListEntry.SPACER}$channelId"
}
