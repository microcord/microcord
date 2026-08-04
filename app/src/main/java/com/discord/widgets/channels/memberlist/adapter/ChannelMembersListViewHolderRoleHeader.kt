package com.discord.widgets.channels.memberlist.adapter

import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.databinding.WidgetChannelMembersListItemHeaderBinding
import com.discord.widgets.roles.RoleIconView

class ChannelMembersListViewHolderRoleHeader(private val binding: WidgetChannelMembersListItemHeaderBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(data: ChannelMembersListAdapter.Item.RoleHeader) {
        val text = binding.root.findViewById<android.widget.TextView>(R.id.channel_members_list_item_header_text)
        text.text = binding.root.context.getString(R.string.manticord_member_group_count, data.roleName, data.memberCount)
        ViewCompat.setAccessibilityHeading(text, true)
        binding.root.findViewById<RoleIconView>(R.id.channel_members_list_item_header_role_icon)
            .setRole(data.roleId, data.guildId)
    }
}
