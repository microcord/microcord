package com.discord.widgets.channels

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.databinding.WidgetGroupInviteFriendsItemBinding
import com.discord.models.user.User
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.presence.PresenceUtils

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
            binding.friendsListItemName.text = data.user?.username
            binding.friendsListItemStatus.setPresence(data.presence)
            binding.userSelectedCheckbox.isChecked = data.isSelected
            binding.userSelectedCheckbox.contentDescription = data.user?.username
            binding.friendContainer.setOnClickListener {
                data.user?.let { adapter.listener?.invoke(it, !data.isSelected) }
            }
            binding.friendContainer.contentDescription = data.user?.username
            PresenceUtils.setPresenceText(
                data.presence,
                data.isApplicationStreaming,
                binding.friendsListItemGame,
                showFallbackStatusText = true,
            )
            IconUtils.setIcon(binding.friendsListItemAvatar, data.user, R.dimen.avatar_size_standard)
        }
    }
}
