package com.discord.widgets.channels.memberlist.adapter

import androidx.constraintlayout.widget.Guideline
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.databinding.WidgetChannelMembersListItemLoadingBinding

class ChannelMembersListViewHolderLoading(private val binding: WidgetChannelMembersListItemLoadingBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(position: Int) {
        binding.root.findViewById<Guideline>(R.id.username_placeholder_end_guideline)
            .setGuidelinePercent(POSITION_PERCENT_MAP[position % POSITION_PERCENT_MAP.size])
    }

    companion object {
        private val POSITION_PERCENT_MAP = floatArrayOf(0.7f, 0.3f, 0.6f, 0.4f, 0.6f, 0.8f, 0.3f, 0.5f, 0.7f, 0.4f)
    }
}
