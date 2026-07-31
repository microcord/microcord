package com.discord.widgets.chat.list.entries

class LoadingEntry : ChatListEntry() {
    override val type: Int = ChatListEntry.LOAD_INDICATOR
    override val key: String = ChatListEntry.LOAD_INDICATOR.toString()
}
