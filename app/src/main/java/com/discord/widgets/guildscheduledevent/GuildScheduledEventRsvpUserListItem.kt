package com.discord.widgets.guildscheduledevent

import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

/**
 * A row of the details sheet's "Interested" tab.
 *
 * The row is flattened rather than carrying a `GuildScheduledEventUser`: the adapter needs nothing
 * but these seven fields, and flattening lets the diff compare rows without reaching through two
 * model objects.
 */
sealed class GuildScheduledEventRsvpUserListItem : MGRecyclerDataPayload {

    data class RsvpUser(
        val userId: Long,
        val username: String,
        val globalName: String?,
        val userAvatar: String?,
        val discriminator: Int,
        val nickname: String?,
        val guildAvatar: String?,
        val guildId: Long,
    ) : GuildScheduledEventRsvpUserListItem() {

        override val type: Int = TYPE_EVENT_RSVP_USER
        override val key: String = userId.toString()

        /** Guild nickname, else the account display name, else the handle. */
        val displayName: String =
            nickname ?: globalName?.takeUnless(String::isBlank) ?: username

        companion object {
            fun from(guildScheduledEventUser: GuildScheduledEventUser, guildId: Long): RsvpUser =
                RsvpUser(
                    userId = guildScheduledEventUser.user.id,
                    username = guildScheduledEventUser.user.username,
                    globalName = guildScheduledEventUser.user.globalName,
                    userAvatar = guildScheduledEventUser.user.avatar,
                    discriminator = guildScheduledEventUser.user.discriminator,
                    nickname = guildScheduledEventUser.guildMember.nick,
                    guildAvatar = guildScheduledEventUser.guildMember.avatarHash,
                    guildId = guildId,
                )
        }
    }

    companion object {
        const val TYPE_EVENT_RSVP_USER: Int = 0
    }
}
