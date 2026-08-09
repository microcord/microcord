package com.discord.widgets.chat.list.entries

import com.discord.models.message.Message
/** Guild invite card resolved from a message link. */
data class InviteEntry(
    val userId: Long,
    val messageId: Long,
    val inviteCode: String,
    val guildId: Long,
    val eventId: String?,
    val message: Message,
) : ChatListEntry() {

    override val highlightMessage: Message get() = message
    override val type: Int = ChatListEntry.INVITE
    override val key: String = "${ChatListEntry.INVITE} -- $messageId -- $inviteCode"
}
