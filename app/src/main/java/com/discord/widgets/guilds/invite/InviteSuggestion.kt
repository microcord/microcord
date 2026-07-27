package com.discord.widgets.guilds.invite

import com.discord.api.channel.Channel
import com.discord.models.user.User

/**
 * A place an invite can be sent to, before it is turned into a row.
 *
 * [ChannelItem] is an existing private channel — a DM or a group DM the user has already talked
 * in — while [UserSuggestion] is an affinity-ranked friend the user has no channel with yet, so
 * sending to one costs an extra `POST /users/@me/channels`.
 */
sealed class InviteSuggestion {
    data class ChannelItem(val channel: Channel) : InviteSuggestion()
    data class UserSuggestion(val user: User) : InviteSuggestion()
}
