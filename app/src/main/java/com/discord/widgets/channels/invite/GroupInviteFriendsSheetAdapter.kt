package com.discord.widgets.channels.invite

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.databinding.GroupInviteFriendsSheetItemBinding
import com.discord.models.user.User
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.user.UserUtils.displayName

class GroupInviteFriendsSheetAdapter(
    recycler: RecyclerView,
    val onUserChecked: (User, Boolean) -> Unit,
) : MGRecyclerAdapterSimple<GroupInviteFriendsSheetAdapter.FriendItem>(recycler, false) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MGRecyclerViewHolder<*, FriendItem> =
        if (viewType == FriendItem.TYPE_FRIEND) {
            GroupInviteFriendsListItem(this)
        } else {
            throw invalidViewTypeException(viewType)
        }

    data class FriendItem(
        val user: User,
        val isSelected: Boolean,
    ) : MGRecyclerDataPayload {
        override val key: String = user.id.toString()
        override val type: Int = TYPE_FRIEND

        companion object {
            const val TYPE_FRIEND = 0

            fun createItems(users: Collection<User>, checkedUserIds: Set<Long>): ArrayList<FriendItem> =
                ArrayList<FriendItem>(users.size).apply {
                    users.forEach { add(FriendItem(it, it.id in checkedUserIds)) }
                }
        }
    }

    class GroupInviteFriendsListItem(
        adapter: GroupInviteFriendsSheetAdapter,
    ) : MGRecyclerViewHolder<GroupInviteFriendsSheetAdapter, FriendItem>(
        R.layout.group_invite_friends_sheet_item,
        adapter,
    ) {
        private val binding = GroupInviteFriendsSheetItemBinding.bind(itemView)

        override fun onConfigure(position: Int, data: FriendItem) {
            binding.friendsListItemName.text = data.user.displayName
            IconUtils.setIcon(binding.friendsListItemAvatar, data.user, R.dimen.avatar_size_standard)
            binding.userSelectedCheckbox.isChecked = data.isSelected
            binding.userSelectedCheckbox.contentDescription = data.user.username
            binding.userSelectedCheckbox.isClickable = false
            binding.root.apply {
                isClickable = true
                isFocusable = true
                setOnClickListener { adapter.onUserChecked(data.user, !data.isSelected) }
            }
        }
    }
}
