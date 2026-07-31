package com.discord.widgets.channels.memberlist.adapter

import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.databinding.WidgetChannelMembersListItemHeaderBinding

class ChannelMembersListViewHolderHeader(private val binding: WidgetChannelMembersListItemHeaderBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(data: ChannelMembersListAdapter.Item.Header) {
        val text = binding.root.findViewById<android.widget.TextView>(R.id.channel_members_list_item_header_text)
        val label = binding.root.context.getString(when (data.headerType) {
            ChannelMembersListAdapter.Item.Header.Type.ONLINE -> R.string.status_online
            ChannelMembersListAdapter.Item.Header.Type.OFFLINE -> R.string.status_offline
            ChannelMembersListAdapter.Item.Header.Type.GROUP_DM -> R.string.members
        })
        text.text = binding.root.context.getString(R.string.manticord_member_group_count, label, data.memberCount)
        binding.root.findViewById<android.view.View>(R.id.channel_members_list_item_header_role_icon).visibility = android.view.View.GONE
        ViewCompat.setAccessibilityHeading(text, true)
    }
}
