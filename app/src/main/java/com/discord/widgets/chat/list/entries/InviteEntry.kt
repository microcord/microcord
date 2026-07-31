package com.discord.widgets.chat.list.entries

/** Guild invite card resolved from a message link. */
data class InviteEntry(
    val userId: Long,
    val messageId: Long,
    val inviteCode: String,
    val guildId: Long,
    val eventId: String?,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.INVITE
    override val key: String = "${ChatListEntry.INVITE} -- $messageId -- $inviteCode"
}
