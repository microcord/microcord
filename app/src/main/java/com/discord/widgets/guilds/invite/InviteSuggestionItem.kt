package com.discord.widgets.guilds.invite

import com.discord.api.channel.Channel
import com.discord.models.user.User
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

/**
 * Rows of the full-screen invite-share suggestion list ([PrivateChannelAdapter]).
 *
 * The base [type] is `-1` and only [SearchNoResultsItem] leaves it alone: that adapter inflates a
 * single layout for every row, so the view type is never dispatched on. The compact variants use
 * [InviteSuggestionItemV2] instead, which does dispatch.
 *
 * [hasSentInvite] is `true` for the empty-results row so the shared binding never shows an
 * "Invite" button on it.
 */
sealed class InviteSuggestionItem : MGRecyclerDataPayload {

    override val type: Int get() = TYPE_DEFAULT

    abstract fun hasSentInvite(): Boolean

    data class ChannelItem(val channel: Channel, val hasSent: Boolean) : InviteSuggestionItem() {
        override val type: Int get() = TYPE_CHANNEL
        override val key: String get() = "c${channel.id}"
        override fun hasSentInvite(): Boolean = hasSent
    }

    data class UserItem(val user: User, val hasSent: Boolean) : InviteSuggestionItem() {
        override val key: String get() = "u${user.id}"
        override fun hasSentInvite(): Boolean = hasSent
    }

    object SearchNoResultsItem : InviteSuggestionItem() {
        override val key: String get() = "SEARCH_NO_RESULTS"
        override fun hasSentInvite(): Boolean = true
    }

    companion object {
        private const val TYPE_DEFAULT = -1
        private const val TYPE_CHANNEL = 1
    }
}
