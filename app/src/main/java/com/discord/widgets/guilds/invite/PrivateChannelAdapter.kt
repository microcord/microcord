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
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

/**
 * Suggestion list of the full-screen invite-share flow.
 *
 * One layout for every row: a 1:1 DM is rendered as its *recipient* (avatar + username) rather
 * than as a channel, because the channel's own name is empty for a DM and its icon would be the
 * generic channel glyph.
 *
 * Diffing is disabled (`deferredDiffs = false` in the reference, via the same two-argument
 * super-call): the list is rebuilt on every keystroke of the search field, and a deferred diff
 * would land after the next one.
 */
class PrivateChannelAdapter(
    recycler: RecyclerView,
) : MGRecyclerAdapterSimple<InviteSuggestionItem>(recycler, deferredDiffs = false) {

    var onClick: (InviteSuggestionItem) -> Unit = {}

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<PrivateChannelAdapter, InviteSuggestionItem> = Item(this)

    class Item(
        adapter: PrivateChannelAdapter,
    ) : MGRecyclerViewHolder<PrivateChannelAdapter, InviteSuggestionItem>(
        R.layout.widget_guild_invite_share_item,
        adapter,
    ) {
        private val icon: ImageView = itemView.findViewById(R.id.item_icon_iv)
        private val name: TextView = itemView.findViewById(R.id.item_name_tv)
        private val inviteButton: View = itemView.findViewById(R.id.item_invite_btn)
        private val sentLabel: View = itemView.findViewById(R.id.item_sent)

        override fun onConfigure(position: Int, data: InviteSuggestionItem) {
            super.onConfigure(position, data)
            when (data) {
                is InviteSuggestionItem.ChannelItem -> configureChannel(data.channel)
                is InviteSuggestionItem.UserItem -> {
                    IconUtils.setIcon(icon, data.user)
                    name.text = data.user.username
                }
                InviteSuggestionItem.SearchNoResultsItem -> Unit
            }

            if (data === InviteSuggestionItem.SearchNoResultsItem) return

            sentLabel.visibility = if (data.hasSentInvite()) View.VISIBLE else View.GONE
            inviteButton.visibility = if (data.hasSentInvite()) View.GONE else View.VISIBLE
            inviteButton.setOnClickListener { adapter.onClick(data) }
        }

        private fun configureChannel(channel: Channel) {
            if (channel.type == Channel.DM) {
                IconUtils.setIcon(icon, channel.getDMRecipient())
            } else {
                IconUtils.setIcon(icon, channel)
            }
            name.text = channel.getDisplayName()
        }
    }
}
