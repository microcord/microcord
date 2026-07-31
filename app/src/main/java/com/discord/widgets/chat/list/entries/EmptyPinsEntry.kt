package com.discord.widgets.chat.list.entries

data class EmptyPinsEntry(val text: String) : ChatListEntry() {
    override val type: Int = ChatListEntry.EMPTY_PINS
    override val key: String = ChatListEntry.EMPTY_PINS.toString()
}
