package com.discord.widgets.chat.list.entries

import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.models.guild.Guild
import com.discord.models.message.Message

data class MessageHeaderEntry(
    val message: Message,
    val text: String?,
    val channelName: String?,
) : ChatListEntry() {

    override val highlightMessage: Message get() = message
    constructor(message: Message, channel: Channel?, guild: Guild?) : this(
        message,
        guild?.name,
        channel?.getDisplayName(),
    )

    override val type: Int = ChatListEntry.MESSAGE_HEADER
    override val key: String = ChatListEntry.MESSAGE_HEADER.toString()
}
