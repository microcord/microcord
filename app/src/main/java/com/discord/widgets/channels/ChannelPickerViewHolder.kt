package com.discord.widgets.channels

import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.getDisplayName
import com.discord.databinding.ViewGuildRoleSubscriptionChannelItemBinding
import com.discord.utilities.channel.guildChannelIcon
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.color.setTint

sealed class ChannelPickerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    class ChannelItemViewHolder(
        private val binding: ViewGuildRoleSubscriptionChannelItemBinding,
        onItemClickListener: (ChannelPickerAdapterItem.ChannelItem) -> Unit,
    ) : ChannelPickerViewHolder(binding.root) {
        private var item: ChannelPickerAdapterItem.ChannelItem? = null

        init {
            itemView.setOnClickListener { item?.let(onItemClickListener) }
        }

        fun configure(adapterItem: ChannelPickerAdapterItem) {
            val channelItem = adapterItem as ChannelPickerAdapterItem.ChannelItem
            item = channelItem
            binding.channelItemName.text = channelItem.channel.getDisplayName()
            binding.channelItemTypeIcon.setImageResource(guildChannelIcon(channelItem.channel))
            binding.channelItemSelected.isVisible = channelItem.isSelected
        }
    }

    class CreateChannelViewHolder(
        binding: ViewGuildRoleSubscriptionChannelItemBinding,
        onItemClickListener: () -> Unit,
    ) : ChannelPickerViewHolder(binding.root) {
        init {
            binding.channelItemName.setText(R.string.guild_role_subscription_benefit_channel_select_create_label)
            val color = ColorCompat.getThemedColor(itemView, R.attr.colorTextLink)
            binding.channelItemName.setTextColor(color)
            binding.channelItemTypeIcon.setImageDrawable(
                ContextCompat.getDrawable(itemView.context, R.drawable.ic_add_24dp)?.apply { setTint(color) },
            )
            binding.channelItemSelected.isVisible = false
            itemView.setOnClickListener { onItemClickListener() }
        }
    }
}
