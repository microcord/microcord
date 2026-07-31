package com.discord.widgets.settings.guildboost

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.domain.ModelGuildBoostSlot
import com.discord.models.guild.Guild
import com.discord.utilities.icon.IconUtils
import com.discord.views.guildboost.GuildBoostProgressView
import com.facebook.drawee.view.SimpleDraweeView
import java.util.Date

class WidgetSettingsGuildBoostSubscriptionAdapter(
    @Suppress("UNUSED_PARAMETER") recycler: RecyclerView,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    sealed class Item(val type: Int, val key: String) {
        data class GuildItem(val guild: Guild?, val boostCount: Int) : Item(TYPE_GUILD, guild?.id.toString())
        data class GuildBoostItem(val boostSlot: ModelGuildBoostSlot, val boostEndsAt: String?) : Item(TYPE_GUILD_BOOST, boostSlot.id.toString())
        data class HeaderItem(val headerStringId: Int) : Item(TYPE_HEADER, headerStringId.toString())

        companion object {
            const val TYPE_GUILD = 0
            const val TYPE_GUILD_BOOST = 1
            const val TYPE_HEADER = 2
        }
    }

    private var items = emptyList<Item>()
    private var subscribe: (Long) -> Unit = {}
    private var transfer: (ModelGuildBoostSlot, Long) -> Unit = { _, _ -> }
    private var cancel: (Long, Boolean) -> Unit = { _, _ -> }
    private var canCancel = false
    private var canUncancel = false

    fun configure(
        guildBoostItems: List<Item>,
        subscribeListener: (Long) -> Unit,
        transferListener: (ModelGuildBoostSlot, Long) -> Unit,
        cancelListener: (Long, Boolean) -> Unit,
        canCancelBoosts: Boolean,
        canUncancelBoosts: Boolean,
    ) {
        items = guildBoostItems
        subscribe = subscribeListener
        transfer = transferListener
        cancel = cancelListener
        canCancel = canCancelBoosts
        canUncancel = canUncancelBoosts
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = items[position].type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            Item.TYPE_GUILD -> GuildListItem(inflater.inflate(R.layout.view_settings_boosted_guild_listitem, parent, false))
            Item.TYPE_GUILD_BOOST -> GuildBoostListItem(inflater.inflate(R.layout.view_settings_boosted_boost_listitem, parent, false))
            Item.TYPE_HEADER -> HeaderListItem(inflater.inflate(R.layout.view_settings_boosted_header_listitem, parent, false))
            else -> error("Invalid boost item type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = when (holder) {
        is GuildListItem -> holder.bind(items[position] as Item.GuildItem)
        is GuildBoostListItem -> holder.bind(items[position] as Item.GuildBoostItem)
        is HeaderListItem -> holder.bind(items[position] as Item.HeaderItem)
        else -> Unit
    }

    inner class GuildBoostListItem(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val action: TextView = itemView.findViewById(R.id.boosted_boost_action)
        private val cancelled: View = itemView.findViewById(R.id.boosted_boost_cancelled)
        private val cooldown: TextView = itemView.findViewById(R.id.boosted_boost_cooldown)
        private val date: TextView = itemView.findViewById(R.id.boosted_boost_date)

        fun bind(item: Item.GuildBoostItem) {
            val slot = item.boostSlot
            val applied = slot.premiumGuildSubscription
            val coolingDown = slot.cooldownExpiresAtTimestamp > System.currentTimeMillis()
            cancelled.visibility = if (slot.canceled) View.VISIBLE else View.GONE
            date.text = applied?.id?.let { DateFormat.format("MMMM dd, yyyy", Date((it ushr 22) + 1420070400000L)) }
                ?: itemView.context.getString(R.string.premium_guild_subscription_unused_slot_description)
            cooldown.visibility = if (coolingDown) View.VISIBLE else View.GONE
            if (coolingDown) cooldown.text = itemView.context.getString(R.string.premium_guild_subscription_transfer_button_disabled_tooltip)
            action.text = if (applied == null && !coolingDown) itemView.context.getString(R.string.premium_guild_subscription_select_server_button) else ""
            action.setOnClickListener { source ->
                if (applied == null && !coolingDown) subscribe(slot.id) else showActions(source, item)
            }
        }

        private fun showActions(source: View, item: Item.GuildBoostItem) {
            val applied = item.boostSlot.premiumGuildSubscription
            PopupMenu(source.context, source).apply {
                if (!item.boostSlot.canceled && applied != null) menu.add(R.string.premium_guild_subscription_transfer_button).setOnMenuItemClickListener {
                    transfer(item.boostSlot, applied.guildId); true
                }
                if (!item.boostSlot.canceled && canCancel) menu.add(R.string.cancel).setOnMenuItemClickListener {
                    cancel(item.boostSlot.id, true); true
                }
                if (item.boostSlot.canceled && canUncancel) menu.add(R.string.premium_guild_subscription_slot_uncancel_button_mobile).setOnMenuItemClickListener {
                    cancel(item.boostSlot.id, false); true
                }
                show()
            }
        }
    }

    class GuildListItem(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val banner: SimpleDraweeView = itemView.findViewById(R.id.boosted_guild_banner)
        private val count: TextView = itemView.findViewById(R.id.boosted_sample_guild_count)
        private val icon: SimpleDraweeView = itemView.findViewById(R.id.boosted_sample_guild_icon)
        private val name: TextView = itemView.findViewById(R.id.boosted_sample_guild_name)
        private val progress: GuildBoostProgressView = itemView.findViewById(R.id.boosted_guild_progress_view)

        fun bind(item: Item.GuildItem) {
            val guild = requireNotNull(item.guild)
            IconUtils.setIcon(icon, guild)
            banner.setImageURI(IconUtils.getBannerForGuild(guild, 1024))
            name.text = guild.name
            count.text = itemView.resources.getQuantityString(R.plurals.guild_settings_premium_guild_tier_requirement_required, item.boostCount, item.boostCount)
            progress.configure(guild.id, guild.premiumTier, guild.premiumSubscriptionCount)
        }
    }

    class HeaderListItem(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(item: Item.HeaderItem) { (itemView as TextView).setText(item.headerStringId) }
    }
}
