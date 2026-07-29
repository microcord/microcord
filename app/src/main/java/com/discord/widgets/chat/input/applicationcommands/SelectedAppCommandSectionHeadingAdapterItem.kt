package com.discord.widgets.chat.input.applicationcommands

import com.discord.R
import com.discord.databinding.ViewAppcommandsOptionheadingListitemBinding
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

class SelectedAppCommandSectionHeadingAdapterItem(
    adapter: SelectedApplicationCommandAdapter,
) : MGRecyclerViewHolder<SelectedApplicationCommandAdapter, SelectedApplicationCommandItem>(
    R.layout.view_appcommands_optionheading_listitem,
    adapter,
) {
    private val binding = ViewAppcommandsOptionheadingListitemBinding.bind(itemView)

    override fun onConfigure(position: Int, data: SelectedApplicationCommandItem) {
        super.onConfigure(position, data)
        binding.appcommandsOptionheadingTitle.text = data.heading
    }
}
