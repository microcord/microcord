package com.discord.widgets.chat.list.entries

class SearchErrorEntry : ChatListEntry() {
    override val type: Int = ChatListEntry.SEARCH_ERROR
    override val key: String = ChatListEntry.SEARCH_ERROR.toString()
}
