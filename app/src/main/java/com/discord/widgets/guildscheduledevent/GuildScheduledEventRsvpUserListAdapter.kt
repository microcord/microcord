package com.discord.widgets.guildscheduledevent

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.user.UserUtils
import com.facebook.drawee.view.SimpleDraweeView

/** The attendee list shown on the details sheet's "Interested" tab. */
class GuildScheduledEventRsvpUserListAdapter(
    recyclerView: RecyclerView,
    private val onItemClick: (userId: Long) -> Unit,
) : MGRecyclerAdapterSimple<GuildScheduledEventRsvpUserListItem>(recyclerView) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, GuildScheduledEventRsvpUserListItem> = when (viewType) {
        GuildScheduledEventRsvpUserListItem.TYPE_EVENT_RSVP_USER -> UserViewHolder()
        else -> throw invalidViewTypeException(viewType)
    }

    inner class UserViewHolder :
        MGRecyclerViewHolder<GuildScheduledEventRsvpUserListAdapter, GuildScheduledEventRsvpUserListItem>(
            R.layout.guild_scheduled_event_rsvp_user_list_adapter_item,
            this@GuildScheduledEventRsvpUserListAdapter,
        ) {
        private val avatar: SimpleDraweeView = itemView.findViewById(R.id.user_profile_adapter_item_avatar)
        private val discriminator: TextView = itemView.findViewById(R.id.user_profile_adapter_item_discriminator)
        private val userName: TextView = itemView.findViewById(R.id.user_profile_adapter_item_user_name)

        @SuppressLint("SetTextI18n")
        override fun onConfigure(position: Int, data: GuildScheduledEventRsvpUserListItem) {
            super.onConfigure(position, data)
            val item = data as GuildScheduledEventRsvpUserListItem.RsvpUser
            itemView.setOnClickListener { adapter.onItemClick(item.userId) }

            userName.text = item.displayName
            // A nickname replaces `username#0001` entirely, so the tag is only meaningful without
            // one. The row is recycled, so the previous item's tag has to be cleared explicitly.
            if (item.nickname == null) {
                discriminator.text = UserUtils.padDiscriminator(item.discriminator)
                discriminator.visibility = View.VISIBLE
            } else {
                discriminator.text = ""
                discriminator.visibility = View.GONE
            }

            val avatarUrl = if (item.guildAvatar != null) {
                IconUtils.getForGuildMember(item.guildAvatar, item.guildId, item.userId)
            } else {
                // The reference omits the discriminator here, so an avatar-less attendee falls
                // back to default avatar 0 rather than their own bucket. Kept for parity.
                IconUtils.getForUser(item.userId, item.userAvatar)
            }
            if (avatarUrl != null) {
                IconUtils.setIcon(avatar, avatarUrl, R.dimen.avatar_size_standard)
            }
        }
    }
}
