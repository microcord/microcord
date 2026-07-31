package com.discord.widgets.channels.memberlist.adapter

import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.databinding.WidgetChannelMembersListItemAddOrLeaveBinding

class ChannelMembersListViewHolderAdd(private val binding: WidgetChannelMembersListItemAddOrLeaveBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(onClicked: () -> Unit, title: Int) {
        binding.root.findViewById<android.widget.TextView>(R.id.channel_members_list_item_add_or_leave_title).setText(title)
        binding.root.findViewById<android.widget.ImageView>(R.id.channel_members_list_item_add_or_leave)
            .setImageResource(R.drawable.ic_add_person_16dp)
        binding.root.setOnClickListener { onClicked() }
    }
}
