package com.discord.widgets.channels.memberlist.adapter

import android.content.res.ColorStateList
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.databinding.WidgetChannelMembersListItemAddOrLeaveBinding
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.drawable.getThemedDrawableRes

class ChannelMembersListViewHolderJoinLeaveThread(private val binding: WidgetChannelMembersListItemAddOrLeaveBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(onClicked: () -> Unit, isThreadJoined: Boolean) {
        val context = binding.root.context
        binding.root.setOnClickListener { onClicked() }
        binding.root.findViewById<android.widget.TextView>(R.id.channel_members_list_item_add_or_leave_title)
            .setText(if (isThreadJoined) R.string.leave_thread else R.string.join_thread)
        binding.root.findViewById<android.widget.ImageView>(R.id.channel_members_list_item_add_or_leave).apply {
            setImageResource(context.getThemedDrawableRes(if (isThreadJoined) R.attr.ic_exit_to_app else R.attr.ic_person_add))
            imageTintList = ColorStateList.valueOf(ColorCompat.getThemedColor(
                context,
                if (isThreadJoined) R.attr.colorTextDanger else R.attr.colorInteractiveNormal,
            ))
        }
    }
}
