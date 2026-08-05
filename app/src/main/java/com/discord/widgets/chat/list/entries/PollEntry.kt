package com.discord.widgets.chat.list.entries

import com.discord.api.message.MessagePoll
import com.discord.models.message.Message

data class PollEntry(
    val message: Message,
    val poll: MessagePoll,
    override val isPendingReply: Boolean = false,
) : ChatListEntry() {
    override val type: Int = POLL
    override val key: String = "$POLL-${message.id}"
}
