package com.discord.widgets.chat.list.entries

import com.discord.models.message.Message
import com.discord.models.domain.ModelInvite

/** Invite resolved to a live public stage. */
data class StageInviteEntry(
    val userId: Long,
    val messageId: Long,
    val inviteCode: String,
    val invite: ModelInvite,
    val guildId: Long,
    val message: Message,
) : ChatListEntry() {

    override val highlightMessage: Message get() = message
    override val type: Int = ChatListEntry.STAGE_INVITE
    override val key: String = "${ChatListEntry.STAGE_INVITE} -- $messageId -- $inviteCode"
}
