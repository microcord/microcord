package com.discord.widgets.chat.list.entries

import com.discord.models.message.Message
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.models.domain.ModelInvite

/** Invite resolved to a scheduled guild event. */
data class GuildScheduledEventInviteEntry(
    val invite: ModelInvite,
    val inviteCode: String,
    val guildScheduledEvent: GuildScheduledEvent,
    val message: Message,
) : ChatListEntry() {

    override val highlightMessage: Message get() = message
    override val type: Int = ChatListEntry.GUILD_SCHEDULED_EVENT_INVITE
    override val key: String =
        "${ChatListEntry.GUILD_SCHEDULED_EVENT_INVITE} -- ${guildScheduledEvent.id} -- $inviteCode"
}
