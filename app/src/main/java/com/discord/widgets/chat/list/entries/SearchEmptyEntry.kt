package com.discord.widgets.chat.list.entries

class SearchEmptyEntry : ChatListEntry() {
    override val type: Int = ChatListEntry.SEARCH_EMPTY
    override val key: String = ChatListEntry.SEARCH_EMPTY.toString()
}
