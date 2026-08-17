package com.discord.widgets.settings.guildboost

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.guild.Guild
import com.discord.utilities.icon.IconUtils
import com.discord.views.guildboost.GuildBoostProgressView
import com.discord.views.VitoImageView

class SettingsGuildBoostSampleGuildAdapter :
    RecyclerView.Adapter<SettingsGuildBoostSampleGuildAdapter.GuildListItem>() {
    data class Item(val guild: Guild)

    private var items = emptyList<Item>()
    private var onGuildClickedListener: ((Long) -> Unit)? = null

    fun configure(guildBoostItems: List<Item>, onGuildClickedListener: (Long) -> Unit) {
        items = guildBoostItems
        this.onGuildClickedListener = onGuildClickedListener
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = GuildListItem(
        LayoutInflater.from(parent.context).inflate(R.layout.view_settings_boosted_sample_guild, parent, false),
        onGuildClicked = { onGuildClickedListener?.invoke(it) },
    )

    override fun onBindViewHolder(holder: GuildListItem, position: Int) = holder.bind(items[position])

    class GuildListItem(
        itemView: View,
        private val onGuildClicked: (Long) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val count: TextView = itemView.findViewById(R.id.boosted_sample_guild_count)
        private val icon: VitoImageView = itemView.findViewById(R.id.boosted_sample_guild_icon)
        private val name: TextView = itemView.findViewById(R.id.boosted_sample_guild_name)
        private val progress: GuildBoostProgressView = itemView.findViewById(R.id.boosted_sample_guild_progress)

        fun bind(item: Item) {
            val guild = item.guild
            val boosts = guild.premiumSubscriptionCount
            IconUtils.setIcon(icon, guild)
            name.text = guild.name
            count.text = itemView.resources
                .getQuantityString(R.plurals.guild_settings_premium_guild_tier_requirement_required, boosts)
                .replace("{#}", boosts.toString())
            progress.configure(guild.id, guild.premiumTier, boosts)
            itemView.setOnClickListener { onGuildClicked(guild.id) }
        }
    }
}
