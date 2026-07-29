package com.discord.widgets.chat.input.applicationcommands

import com.discord.R
import com.discord.databinding.ViewAppcommandsOptionparamListitemBinding
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

class SelectedAppCommandOptionAdapterItem(
    adapter: SelectedApplicationCommandAdapter,
) : MGRecyclerViewHolder<SelectedApplicationCommandAdapter, SelectedApplicationCommandItem>(
    R.layout.view_appcommands_optionparam_listitem,
    adapter,
) {
    private val binding = ViewAppcommandsOptionparamListitemBinding.bind(itemView)

    override fun onConfigure(position: Int, data: SelectedApplicationCommandItem) {
        super.onConfigure(position, data)
        val option = data.option ?: return
        val title = binding.appcommandsOptiontitleTitle
        title.text = option.name
        title.setBackgroundResource(
            if (data.highlighted) {
                R.drawable.drawable_bg_command_param_highlight
            } else {
                R.drawable.drawable_bg_command_param_normal
            },
        )
        title.setTextColor(
            if (data.error && !data.highlighted) {
                ColorCompat.getColor(title, R.color.status_red_500)
            } else {
                ColorCompat.getThemedColor(title, R.attr.colorHeaderPrimary)
            },
        )
        title.alpha = if (data.completed && !data.highlighted) 0.5f else 1f
        itemView.setOnClickListener { adapter.onParamClicked(option) }
    }
}
