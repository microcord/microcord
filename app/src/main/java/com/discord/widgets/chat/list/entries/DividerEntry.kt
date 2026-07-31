package com.discord.widgets.chat.list.entries

class DividerEntry : ChatListEntry() {
    override val type: Int = ChatListEntry.DIVIDER
    override val key: String = ChatListEntry.DIVIDER.toString()
}
