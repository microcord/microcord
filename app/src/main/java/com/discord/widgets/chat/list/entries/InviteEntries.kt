package com.discord.widgets.chat.list.entries

import com.discord.api.application.Application
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.message.activity.MessageActivity
import com.discord.models.domain.ModelInvite

/**
 * The cards produced from links and rich-presence payloads inside a message body: guild invites,
 * stage invites, scheduled-event invites, guild templates, Nitro gifts, game invites and Spotify
 * listen-along.
 *
 * The link-derived rows key on `messageId + code`, so a message quoting several invites renders one
 * independently diffable card per code.
 */

/** An ordinary guild invite card. Resolves itself against the invite endpoint when bound. */
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

/** An invite that resolved to a live public stage; shows the stage topic and speakers. */
data class StageInviteEntry(
    val userId: Long,
    val messageId: Long,
    val inviteCode: String,
    val invite: ModelInvite,
    val guildId: Long,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.STAGE_INVITE
    override val key: String = "${ChatListEntry.STAGE_INVITE} -- $messageId -- $inviteCode"
}

/**
 * An invite carrying an `?event=` parameter that resolved to a scheduled event. Keyed on the event
 * rather than the message, because the same event may be linked from several messages.
 */
data class GuildScheduledEventInviteEntry(
    val invite: ModelInvite,
    val inviteCode: String,
    val guildScheduledEvent: GuildScheduledEvent,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.GUILD_SCHEDULED_EVENT_INVITE
    override val key: String =
        "${ChatListEntry.GUILD_SCHEDULED_EVENT_INVITE} -- ${guildScheduledEvent.id} -- $inviteCode"
}

/** A `discord.new` guild-template link. */
data class GuildTemplateEntry(
    val userId: Long,
    val messageId: Long,
    val guildTemplateCode: String,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.GUILD_TEMPLATE
    override val key: String = "${ChatListEntry.GUILD_TEMPLATE} -- $messageId -- $guildTemplateCode"
}

/** A `discord.gift` Nitro gift link. */
data class GiftEntry(
    val userId: Long,
    val messageId: Long,
    val channelId: Long,
    val giftCode: String,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.GIFT
    override val key: String = "${ChatListEntry.GIFT} -- $messageId -- $giftCode"
}

/** A rich-presence game invite ("Join", "Spectate", "Ask to Join"). */
data class GameInviteEntry(
    val authorId: Long,
    val messageId: Long,
    val activity: MessageActivity,
    val application: Application,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.GAME_INVITE
    override val key: String = "${ChatListEntry.GAME_INVITE} -- $messageId"
}

/** A Spotify listen-along invite. */
data class SpotifyListenTogetherEntry(
    val userId: Long,
    val messageId: Long,
    val activity: MessageActivity,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.SPOTIFY_LISTEN_TOGETHER
    override val key: String = "${ChatListEntry.SPOTIFY_LISTEN_TOGETHER} -- $messageId"
}
