package com.discord.widgets.servers.guild_role_subscription

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.discord.R

class ServerSettingsGuildRoleSubscriptionTierAdapter(
    private val listener: ItemClickListener,
) : ListAdapter<ServerSettingsGuildRoleSubscriptionTierAdapterItem, TierViewHolder>(DIFF) {

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is ServerSettingsGuildRoleSubscriptionTierAdapterItem.Tier -> VIEW_TYPE_TIER
        ServerSettingsGuildRoleSubscriptionTierAdapterItem.AddTier -> VIEW_TYPE_ADD_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TierViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_TIER -> TierViewHolder.TierItemViewHolder(
                inflater.inflate(R.layout.view_server_settings_guild_role_subscription_tier_item, parent, false),
            )
            VIEW_TYPE_ADD_ITEM -> TierViewHolder.AddTierItemViewHolder(
                inflater.inflate(R.layout.view_server_settings_guild_role_subscription_add_tier_item, parent, false),
                listener::onAddTierItemClick,
            )
            else -> error("Unknown tier row type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: TierViewHolder, position: Int) {
        val item = getItem(position)
        if (holder is TierViewHolder.TierItemViewHolder && item is ServerSettingsGuildRoleSubscriptionTierAdapterItem.Tier) {
            holder.configureUI(item, listener::onTierItemClick)
        }
    }

    fun setItems(items: List<ServerSettingsGuildRoleSubscriptionTierAdapterItem>) = submitList(items)

    interface ItemClickListener {
        fun onAddTierItemClick()
        fun onTierItemClick(tierListingId: Long)
    }

    private companion object {
        const val VIEW_TYPE_TIER = 0
        const val VIEW_TYPE_ADD_ITEM = 1

        val DIFF = object : DiffUtil.ItemCallback<ServerSettingsGuildRoleSubscriptionTierAdapterItem>() {
            override fun areItemsTheSame(
                oldItem: ServerSettingsGuildRoleSubscriptionTierAdapterItem,
                newItem: ServerSettingsGuildRoleSubscriptionTierAdapterItem,
            ) = oldItem.key == newItem.key

            override fun areContentsTheSame(
                oldItem: ServerSettingsGuildRoleSubscriptionTierAdapterItem,
                newItem: ServerSettingsGuildRoleSubscriptionTierAdapterItem,
            ) = oldItem == newItem
        }
    }
}
