package com.discord.widgets.channels

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.stores.StoreStream
import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.R
import com.discord.databinding.WidgetGroupInviteFriendsItemBinding
import com.discord.models.user.User
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.user.UserUtils.displayName

class WidgetGroupInviteFriendsAdapter(
    recycler: RecyclerView,
) : MGRecyclerAdapterSimple<WidgetGroupInviteFriends.Model.FriendItem>(recycler, false) {
    private var listener: ((User, Boolean) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WidgetGroupInviteFriendsListItem =
        if (viewType == WidgetGroupInviteFriends.Model.FriendItem.TYPE_FRIEND) {
            WidgetGroupInviteFriendsListItem(this)
        } else {
            throw invalidViewTypeException(viewType)
        }

    fun setData(
        data: List<WidgetGroupInviteFriends.Model.FriendItem>,
        friendChosenListener: (User, Boolean) -> Unit,
    ) {
        super.setData(data)
        listener = friendChosenListener
    }

    class WidgetGroupInviteFriendsListItem(
        adapter: WidgetGroupInviteFriendsAdapter,
    ) : MGRecyclerViewHolder<WidgetGroupInviteFriendsAdapter, WidgetGroupInviteFriends.Model.FriendItem>(
        R.layout.widget_group_invite_friends_item,
        adapter,
    ) {
        private val binding = WidgetGroupInviteFriendsItemBinding.bind(itemView)

        override fun onConfigure(position: Int, data: WidgetGroupInviteFriends.Model.FriendItem) {
            val friendName = data.user?.let {
                it.getDisplayName(StoreStream.getUserRelationships().getNickname(it.id))
            }
            binding.friendsListItemName.text = friendName
            binding.friendsListItemStatus.setPresence(data.presence)
            binding.userSelectedCheckbox.isChecked = data.isSelected
            binding.userSelectedCheckbox.contentDescription = friendName
            binding.friendContainer.setOnClickListener {
                data.user?.let { adapter.listener?.invoke(it, !data.isSelected) }
            }
            binding.friendContainer.contentDescription = friendName
            PresenceUtils.setStatusLineWithLeadingIcon(
                data.presence,
                data.isApplicationStreaming,
                binding.friendsListItemGame,
                showFallbackStatusText = true,
            )
            IconUtils.setIcon(binding.friendsListItemAvatar, data.user, R.dimen.avatar_size_standard)
        }
    }
}
