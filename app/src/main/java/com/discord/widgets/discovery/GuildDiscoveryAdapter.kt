package com.discord.widgets.discovery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.discovery.DiscoverableGuild
import com.discord.api.guild.GuildFeature
import com.discord.i18n.I18nSpans
import com.discord.models.guild.Guild
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.views.GuildView
import com.discord.views.directories.ServerDiscoveryHeader
import com.discord.views.guilds.ServerMemberCount
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton

class GuildDiscoveryAdapter(
    private val onGuildClicked: (DiscoverableGuild) -> Unit,
    private val onHubClicked: () -> Unit = {},
    private val onHubGuildClicked: (Guild) -> Unit = {},
    private val onSearchClicked: () -> Unit = {},
) : ListAdapter<GuildDiscoveryAdapter.Item, GuildDiscoveryAdapter.Holder>(DIFF) {

    sealed class Item(val key: String, val layout: Int) {
        data class Hero(val tab: GuildDiscoveryTab) :
            Item("hero", R.layout.guild_discovery_item_hero)

        data class Header(@StringRes val titleRes: Int) :
            Item("header-$titleRes", R.layout.guild_discovery_item_header)

        data class Server(val guild: DiscoverableGuild) :
            Item("guild-${guild.id}", R.layout.guild_discovery_item_guild)

        data class HubGuild(val guild: Guild) :
            Item("hub-guild-${guild.id}", R.layout.guild_discovery_item_hub_guild)

        data class HubCta(val hasJoinedHub: Boolean) :
            Item("hub-cta", R.layout.guild_discovery_item_hub)

        data class Empty(@StringRes val titleRes: Int) :
            Item("empty-$titleRes", R.layout.guild_discovery_item_empty)
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) = getItem(position).key.hashCode().toLong()
    override fun getItemViewType(position: Int) = getItem(position).layout

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(viewType, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        bind(holder, getItem(position))
    }

    private fun bind(holder: Holder, item: Item) { when (item) {
        is Item.Header -> (holder.itemView as TextView).setText(item.titleRes)
        is Item.Empty -> (holder.itemView as TextView).let {
            it.text = I18nSpans.render(it.context.getString(item.titleRes))
        }
        is Item.Server -> bindServer(holder, item.guild)
        is Item.HubGuild -> bindHubGuild(holder, item.guild)
        is Item.HubCta -> bindHubCta(holder, item.hasJoinedHub)
        is Item.Hero -> bindHero(holder, item.tab)
    } }

    private fun bindHero(holder: Holder, tab: GuildDiscoveryTab) =
        with<View, Unit>(holder.itemView) {
            val hero = this as ServerDiscoveryHeader
            hero.setTitle(context.getString(tab.bannerTitle))
            hero.setDescription(context.getString(tab.bannerDescription))
            hero.setButtonVisible(tab.isSearchable)
            hero.setButtonOnClickListener { onSearchClicked() }
        }


    private fun bindServer(holder: Holder, guild: DiscoverableGuild) = with<View, Unit>(holder.itemView) {
        val banner = findViewById<SimpleDraweeView>(R.id.discovery_card_banner)
        MGImages.setImage(
            banner,
            IconUtils.getBannerForGuild(guild.id, guild.banner)
                ?: guild.splash?.let { IconUtils.getGuildSplashUrl(guild.id, it) },
        )
        findViewById<GuildView>(R.id.discovery_card_icon).apply {
            useGuildIconCorners()
            configure(IconUtils.getForGuild(guild.id, guild.icon, size = 56), guild.name)
        }
        findViewById<ImageView>(R.id.discovery_card_badge).apply {
            val badge = when {
                GuildFeature.VERIFIED in guild.features -> R.drawable.ic_verified_badge
                GuildFeature.PARTNERED in guild.features -> R.drawable.ic_partnered_badge
                else -> null
            }
            badge?.let { setImageResource(it) }
            visibility = if (badge == null) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.discovery_card_name).text = guild.name
        findViewById<TextView>(R.id.discovery_card_description).apply {
            text = guild.description
            visibility = if (guild.description.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        findViewById<ServerMemberCount>(R.id.discovery_card_counts).apply {
            guild.approximatePresenceCount?.let { setOnline(it) }
            guild.approximateMemberCount?.let { setMembers(it) }
        }
        setOnClickListener { onGuildClicked(guild) }
    }

    private fun bindHubGuild(holder: Holder, guild: Guild) = with<View, Unit>(holder.itemView) {
        findViewById<GuildView>(R.id.discovery_hub_guild_icon).apply {
            useGuildIconCorners()
            configure(IconUtils.getForGuild(guild.id, guild.icon, size = 40), guild.shortName)
        }
        findViewById<TextView>(R.id.discovery_hub_guild_name).text = guild.name
        setOnClickListener { onHubGuildClicked(guild) }
    }

    private fun bindHubCta(holder: Holder, hasJoinedHub: Boolean) = with<View, Unit>(holder.itemView) {
        findViewById<TextView>(R.id.discovery_hub_title).setText(
            if (hasJoinedHub) R.string.hub_email_connection_add_header
            else R.string.global_discovery_servers_hub_title,
        )
        findViewById<TextView>(R.id.discovery_hub_description).setText(
            if (hasJoinedHub) R.string.hub_email_connection_add_description
            else R.string.global_discovery_servers_hub_description,
        )
        findViewById<MaterialButton>(R.id.discovery_hub_button).apply {
            setText(
                if (hasJoinedHub) R.string.hub_email_connection_join_another
                else R.string.hub_discord_hub,
            )
            setOnClickListener { onHubClicked() }
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: Item, newItem: Item) = oldItem == newItem
        }
    }
}
