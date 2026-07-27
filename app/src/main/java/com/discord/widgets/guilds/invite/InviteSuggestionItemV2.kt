package com.discord.widgets.guilds.invite

import com.discord.api.channel.Channel
import com.discord.models.user.User
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

/**
 * Rows of the compact invite-share surfaces ([InviteSuggestionsAdapter]) — the bottom sheet, the
 * compact screen and the empty-suggestions screen.
 *
 * Two things differ from [InviteSuggestionItem]:
 *
 * * [type] really is dispatched on, so the empty-results row gets its own layout; and
 * * the diff [key] folds in [searchQuery]. Without it a row that survives a query change keeps its
 *   old binding, and the reference relies on the rebind to re-run the highlight pass.
 */
sealed class InviteSuggestionItemV2 : MGRecyclerDataPayload {

    abstract fun hasSentInvite(): Boolean

    data class ChannelItem(
        val channel: Channel,
        val hasSent: Boolean,
        val searchQuery: String,
    ) : InviteSuggestionItemV2() {
        override val type: Int get() = TYPE_CHANNEL
        override val key: String get() = "c${channel.id}$searchQuery"
        override fun hasSentInvite(): Boolean = hasSent
    }

    data class UserItem(
        val user: User,
        val hasSent: Boolean,
        val searchQuery: String,
    ) : InviteSuggestionItemV2() {
        override val type: Int get() = TYPE_USER
        override val key: String get() = "u${user.id}$searchQuery"
        override fun hasSentInvite(): Boolean = hasSent
    }

    object SearchNoResultsItem : InviteSuggestionItemV2() {
        override val type: Int get() = TYPE_EMPTY_SEARCH_RESULTS
        override val key: String get() = "SEARCH_NO_RESULTS"
        override fun hasSentInvite(): Boolean = true
    }

    companion object {
        const val TYPE_EMPTY_SEARCH_RESULTS = 1
        const val TYPE_USER = 2
        const val TYPE_CHANNEL = 3
    }
}
