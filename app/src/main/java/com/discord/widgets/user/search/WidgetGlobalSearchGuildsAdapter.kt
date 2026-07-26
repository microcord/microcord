package com.discord.widgets.user.search

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.getDisplayName
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.facebook.drawee.view.SimpleDraweeView

/**
 * The horizontal server/DM rail above the quick-switcher results.
 *
 * Tapping an entry jumps to it; long-pressing a server scopes the search to it instead, which is
 * why both callbacks are exposed separately.
 */
class WidgetGlobalSearchGuildsAdapter(
    recycler: RecyclerView,
) : MGRecyclerAdapterSimple<WidgetGlobalSearchGuildsModel.Item>(recycler) {

    var onClickListener: (WidgetGlobalSearchGuildsModel.Item) -> Unit = {}
    var onLongClickListener: (WidgetGlobalSearchGuildsModel.Item) -> Boolean = { false }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, WidgetGlobalSearchGuildsModel.Item> = when (viewType) {
        WidgetGlobalSearchGuildsModel.Item.TYPE_GUILD -> GuildViewHolder(this)
        WidgetGlobalSearchGuildsModel.Item.TYPE_DM -> DirectMessageViewHolder(this)
        WidgetGlobalSearchGuildsModel.Item.TYPE_DIVIDER -> DividerViewHolder(this)
        else -> throw invalidViewTypeException(viewType)
    }

    private abstract class ClickableViewHolder(
        layout: Int,
        adapter: WidgetGlobalSearchGuildsAdapter,
    ) : MGRecyclerViewHolder<WidgetGlobalSearchGuildsAdapter, WidgetGlobalSearchGuildsModel.Item>(
        layout,
        adapter,
    ) {
        override fun onConfigure(position: Int, data: WidgetGlobalSearchGuildsModel.Item) {
            super.onConfigure(position, data)
            itemView.setOnClickListener { adapter.onClickListener(data) }
            itemView.setOnLongClickListener { adapter.onLongClickListener(data) }
        }
    }

    private class GuildViewHolder(
        adapter: WidgetGlobalSearchGuildsAdapter,
    ) : ClickableViewHolder(R.layout.widget_global_search_item_guild, adapter) {
        private val avatar: SimpleDraweeView = itemView.findViewById(R.id.guilds_item_avatar)
        private val avatarText: TextView = itemView.findViewById(R.id.guilds_item_avatar_text)
        private val mentions: TextView = itemView.findViewById(R.id.guilds_item_mentions)
        private val selected: ImageView = itemView.findViewById(R.id.guilds_item_selected)
        private val unread: ImageView = itemView.findViewById(R.id.guilds_item_unread)

        override fun onConfigure(position: Int, data: WidgetGlobalSearchGuildsModel.Item) {
            super.onConfigure(position, data)
            val guild = data.guild ?: return
            val iconUrl = IconUtils.getForGuild(guild)
            // A server with no icon falls back to its initials, the same as the guilds list.
            IconUtils.setIcon(avatar, iconUrl)
            avatar.visibility = if (iconUrl == null) View.INVISIBLE else View.VISIBLE
            avatarText.text = if (iconUrl == null) guild.shortName else null

            mentions.text = data.mentionCount.takeIf { it > 0 }?.toString()
            mentions.visibility = if (data.mentionCount > 0) View.VISIBLE else View.GONE
            selected.visibility = if (data.isSelected) View.VISIBLE else View.GONE
            unread.visibility = if (data.isUnread && data.mentionCount == 0) View.VISIBLE else View.GONE
            itemView.contentDescription = guild.name
        }
    }

    private class DirectMessageViewHolder(
        adapter: WidgetGlobalSearchGuildsAdapter,
    ) : ClickableViewHolder(R.layout.widget_global_search_item_dm, adapter) {
        private val avatar: SimpleDraweeView = itemView.findViewById(R.id.guilds_item_dm_avatar)
        private val count: TextView = itemView.findViewById(R.id.guilds_item_dm_count)

        override fun onConfigure(position: Int, data: WidgetGlobalSearchGuildsModel.Item) {
            super.onConfigure(position, data)
            val channel = data.channel ?: return
            IconUtils.setIcon(avatar, IconUtils.getForChannel(channel))
            count.text = data.mentionCount.takeIf { it > 0 }?.toString()
            count.visibility = if (data.mentionCount > 0) View.VISIBLE else View.GONE
            itemView.contentDescription = channel.getDisplayName()
        }
    }

    /** Purely decorative; it separates the DM run from the server run and takes no clicks. */
    private class DividerViewHolder(
        adapter: WidgetGlobalSearchGuildsAdapter,
    ) : MGRecyclerViewHolder<WidgetGlobalSearchGuildsAdapter, WidgetGlobalSearchGuildsModel.Item>(
        R.layout.widget_global_search_item_divider,
        adapter,
    )
}
