package com.discord.widgets.hubs

import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.mg_recycler.SingleTypePayload
import com.discord.views.GuildView
import com.google.android.material.card.MaterialCardView

/** The list of hubs a school email matched, on [WidgetHubDomains] and [WidgetHubDomainSearch]. */
class HubDomainAdapter(
    recycler: RecyclerView,
    private val onClickListener: (DomainGuildInfo) -> Unit,
) : MGRecyclerAdapterSimple<SingleTypePayload<DomainGuildInfo>>(recycler) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, SingleTypePayload<DomainGuildInfo>> = HubDomainViewHolder(this, onClickListener)

    fun setGuildInfos(guildInfos: List<DomainGuildInfo>) {
        setData(guildInfos.map { SingleTypePayload(it, it.id.toString()) })
    }
}

/** One matched hub. Binds `R.layout.discord_hub_add_server_list_item`. */
class HubDomainViewHolder(
    adapter: HubDomainAdapter,
    private val onClickListener: (DomainGuildInfo) -> Unit,
) : MGRecyclerViewHolder<HubDomainAdapter, SingleTypePayload<DomainGuildInfo>>(
    R.layout.discord_hub_add_server_list_item,
    adapter,
) {
    private val card: MaterialCardView = itemView.findViewById(R.id.discord_u_add_server_list_item_layout)
    private val guildView: GuildView = itemView.findViewById(R.id.discord_u_add_server_list_item_image)
    private val text: TextView = itemView.findViewById(R.id.discord_u_add_server_list_item_text)

    override fun onConfigure(position: Int, data: SingleTypePayload<DomainGuildInfo>) {
        super.onConfigure(position, data)
        val guildInfo = data.data
        text.text = guildInfo.name
        guildView.useGuildIconCorners()
        // The reference hands GuildView the raw `icon` hash here, which is not a loadable URI and so
        // renders an empty avatar with the initials fallback suppressed. Resolved through IconUtils
        // instead, exactly as the sibling row for already-joined guilds does.
        guildView.configure(
            IconUtils.getForGuild(guildInfo.id, guildInfo.icon),
            computeGuildInitials(guildInfo.name),
        )
        card.setOnClickListener { onClickListener(guildInfo) }
    }
}
