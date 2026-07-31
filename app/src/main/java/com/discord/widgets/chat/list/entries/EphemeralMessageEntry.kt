package com.discord.widgets.chat.list.entries

import com.discord.models.message.Message

class EphemeralMessageEntry(val message: Message) : ChatListEntry() {
    override val type: Int = ChatListEntry.EPHEMERAL_MESSAGE
    override val key: String = "${ChatListEntry.EPHEMERAL_MESSAGE} ${message.nonce ?: message.id}"
}
