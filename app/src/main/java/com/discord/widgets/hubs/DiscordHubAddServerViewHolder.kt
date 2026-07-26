package com.discord.widgets.hubs

import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.guild.Guild
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.mg_recycler.SingleTypePayload
import com.discord.views.GuildView
import com.google.android.material.card.MaterialCardView

/** The guild list on [WidgetHubAddServer]; the same adapter renders both segmented-control tabs. */
class DiscordHubAddServerAdapter(
    recycler: RecyclerView,
    private val onClickListener: (Long) -> Unit,
) : MGRecyclerAdapterSimple<SingleTypePayload<Guild>>(recycler) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, SingleTypePayload<Guild>> = DiscordHubAddServerViewHolder(this, onClickListener)

    fun setGuilds(guilds: List<Guild>) {
        setData(guilds.map { SingleTypePayload(it, it.id.toString()) })
    }
}

/** One of the user's servers. Binds `R.layout.discord_hub_add_server_list_item`. */
class DiscordHubAddServerViewHolder(
    adapter: DiscordHubAddServerAdapter,
    private val onClickListener: (Long) -> Unit,
) : MGRecyclerViewHolder<DiscordHubAddServerAdapter, SingleTypePayload<Guild>>(
    R.layout.discord_hub_add_server_list_item,
    adapter,
) {
    private val card: MaterialCardView = itemView.findViewById(R.id.discord_u_add_server_list_item_layout)
    private val guildView: GuildView = itemView.findViewById(R.id.discord_u_add_server_list_item_image)
    private val text: TextView = itemView.findViewById(R.id.discord_u_add_server_list_item_text)

    override fun onConfigure(position: Int, data: SingleTypePayload<Guild>) {
        super.onConfigure(position, data)
        val guild = data.data
        text.text = guild.name
        guildView.useGuildIconCorners()
        guildView.configure(IconUtils.getForGuild(guild), guild.shortName)
        card.setOnClickListener { onClickListener(guild.id) }
    }
}
