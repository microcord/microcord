package com.discord.widgets.guilds.invite

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDMRecipient
import com.discord.api.channel.getDisplayName
import com.discord.models.user.User
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator

/**
 * Suggestion list of the compact invite surfaces — the bottom sheet, the compact screen and the
 * empty-suggestions screen.
 *
 * Unlike [PrivateChannelAdapter] this one really dispatches on the row type, so the
 * "no results" row gets its own layout instead of an empty shared one.
 *
 * A DM channel is again rendered as its recipient; the difference from the full-screen adapter is
 * that here the name is `username#0001` with the discriminator muted, matching the denser layout.
 */
class InviteSuggestionsAdapter(
    recycler: RecyclerView,
) : MGRecyclerAdapterSimple<InviteSuggestionItemV2>(recycler, deferredDiffs = false) {

    var onClick: (InviteSuggestionItemV2) -> Unit = {}

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<InviteSuggestionsAdapter, InviteSuggestionItemV2> = when (viewType) {
        InviteSuggestionItemV2.TYPE_EMPTY_SEARCH_RESULTS -> EmptySearchResultsViewHolder(this)
        InviteSuggestionItemV2.TYPE_USER,
        InviteSuggestionItemV2.TYPE_CHANNEL,
        -> InviteSuggestionViewHolder(this)
        else -> throw invalidViewTypeException(viewType)
    }

    class EmptySearchResultsViewHolder(
        adapter: InviteSuggestionsAdapter,
    ) : MGRecyclerViewHolder<InviteSuggestionsAdapter, InviteSuggestionItemV2>(
        R.layout.guild_invite_empty_search_results_item,
        adapter,
    )

    class InviteSuggestionViewHolder(
        adapter: InviteSuggestionsAdapter,
    ) : MGRecyclerViewHolder<InviteSuggestionsAdapter, InviteSuggestionItemV2>(
        R.layout.widget_guild_invite_share_item,
        adapter,
    ) {
        private val icon: ImageView = itemView.findViewById(R.id.item_icon_iv)
        private val name: TextView = itemView.findViewById(R.id.item_name_tv)
        private val inviteButton: View = itemView.findViewById(R.id.item_invite_btn)
        private val sentLabel: View = itemView.findViewById(R.id.item_sent)

        override fun onConfigure(position: Int, data: InviteSuggestionItemV2) {
            super.onConfigure(position, data)
            when (data) {
                is InviteSuggestionItemV2.ChannelItem -> {
                    val recipient = data.channel.getDMRecipient()
                    if (recipient != null) configureUser(recipient) else configureChannel(data.channel)
                }
                is InviteSuggestionItemV2.UserItem -> configureUser(data.user)
                InviteSuggestionItemV2.SearchNoResultsItem -> return
            }

            sentLabel.visibility = if (data.hasSentInvite()) View.VISIBLE else View.GONE
            inviteButton.visibility = if (data.hasSentInvite()) View.GONE else View.VISIBLE
            inviteButton.setOnClickListener { adapter.onClick(data) }
        }

        private fun configureChannel(channel: Channel) {
            IconUtils.setIcon(icon, channel)
            name.text = channel.getDisplayName()
        }

        /**
         * The reference calls `UserNameFormatterKt.getSpannableForUserNameWithDiscrim`, which also
         * swaps the font family and text size per run. That helper does not exist in this project,
         * so only the discriminator's colour is applied — the size and font come from the layout.
         */
        private fun configureUser(user: User) {
            IconUtils.setIcon(icon, user)
            name.text = user.getUserNameWithDiscriminator(
                discriminatorColor = ColorCompat.getThemedColor(name, R.attr.colorTextMuted),
            )
        }
    }
}
