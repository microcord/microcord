package com.discord.widgets.chat.list.entries

/** A search hit whose channel is still behind its spoiler gate; the message itself is withheld. */
data class SearchSpoilerResultEntry(val channelId: Long, val messageId: Long) : ChatListEntry() {
    override val type: Int = ChatListEntry.SEARCH_SPOILER_RESULT
    override val key: String = "$SEARCH_SPOILER_RESULT:$messageId"
}
