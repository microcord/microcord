package com.discord.widgets.settings.account

import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.user.User
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton
import com.discord.utilities.user.NicknameUtils
import com.discord.utilities.user.UserUtils.displayName

class WidgetSettingsBlockedUsersAdapter(recycler: RecyclerView) :
    MGRecyclerAdapterSimple<WidgetSettingsBlockedUsersViewModel.Item>(recycler, false) {
    var onClickUnblock: (User) -> Unit = {}
    var onClickUserProfile: (User) -> Unit = {}

    /** Labels the trailing button; the ignored list undoes an ignore rather than a block. */
    var isIgnoredList: Boolean = false

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, WidgetSettingsBlockedUsersViewModel.Item> =
        when (viewType) {
            WidgetSettingsBlockedUsersViewModel.VIEW_TYPE_BLOCKED_USER_ITEM -> BlockedUserViewHolder(this)
            else -> throw invalidViewTypeException(viewType)
        }

    private class BlockedUserViewHolder(
        adapter: WidgetSettingsBlockedUsersAdapter,
    ) : MGRecyclerViewHolder<WidgetSettingsBlockedUsersAdapter, WidgetSettingsBlockedUsersViewModel.Item>(
        R.layout.widget_settings_item_blocked_user,
        adapter,
    ) {
        private val avatar: SimpleDraweeView = itemView.findViewById(R.id.blocked_user_item_avatar)
        private val name: TextView = itemView.findViewById(R.id.blocked_user_item_name)
        private val unblock: MaterialButton = itemView.findViewById(R.id.unblock_user_button)

        override fun onConfigure(position: Int, data: WidgetSettingsBlockedUsersViewModel.Item) {
            super.onConfigure(position, data)
            itemView.setOnClickListener { adapter.onClickUserProfile(data.user) }
            IconUtils.setIcon(avatar, data.user, R.dimen.avatar_size_standard)
            name.text = NicknameUtils.getFriendName(data.user)
            unblock.setText(
                if (adapter.isIgnoredList) {
                    R.string.user_settings_ignored_users_unignorebutton
                } else {
                    R.string.user_settings_blocked_users_unblockbutton
                },
            )
            unblock.setOnClickListener { adapter.onClickUnblock(data.user) }
        }
    }
}
