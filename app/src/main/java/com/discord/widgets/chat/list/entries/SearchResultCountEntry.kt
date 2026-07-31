package com.discord.widgets.chat.list.entries

data class SearchResultCountEntry(val totalResults: Int) : ChatListEntry() {
    override val type: Int = ChatListEntry.SEARCH_RESULT_COUNT
    override val key: String = ChatListEntry.SEARCH_RESULT_COUNT.toString()
}
