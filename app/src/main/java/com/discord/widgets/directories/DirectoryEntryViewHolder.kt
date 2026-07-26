package com.discord.widgets.directories

import com.discord.utilities.icon.IconUtils
import com.discord.views.directories.ServerDiscoveryItem

/**
 * Binds one listed server onto the recovered [ServerDiscoveryItem] view.
 *
 * `directory_entry_list_item.xml` is a bare `ServerDiscoveryItem`, so the row's root *is* the view.
 */
class DirectoryEntryViewHolder(
    private val discoveryItem: ServerDiscoveryItem,
    adapter: WidgetDirectoryChannelAdapter,
) : DirectoryChannelViewHolder(discoveryItem, adapter) {

    override fun bind(item: DirectoryChannelItem, listener: DirectoryChannelItemClickInterface) {
        val entryData = (item as? DirectoryChannelItem.DirectoryItem)?.directoryEntryData ?: return
        val entry = entryData.entry
        val guild = entry.guild

        discoveryItem.setTitle(guild.name)
        discoveryItem.setDescription(entry.description)
        discoveryItem.setMembers(guild.approximateMemberCount ?: 0)
        discoveryItem.setOnline(guild.approximatePresenceCount ?: 0)
        discoveryItem.setGuild(
            IconUtils.getForGuild(guild.id, guild.icon),
            computeShortName(guild.name),
        )

        // Exactly one of the two buttons is live: the other's listener is cleared rather than left
        // pointing at the previous row this recycled holder displayed.
        if (entryData.hasJoinedGuild) {
            discoveryItem.setJoinButtonOnClickListener(null)
            discoveryItem.setJoinedButtonOnClickListener { listener.onGoToGuildClicked(guild.id) }
        } else {
            discoveryItem.setJoinButtonOnClickListener {
                listener.onEntryClicked(guild.id, entry.directoryChannelId)
            }
            discoveryItem.setJoinedButtonOnClickListener(null)
        }
        discoveryItem.setOnLongClickListener {
            listener.onOverflowClicked(entry, entry.directoryChannelId, entryData.hasEditPermissions)
            true
        }
        discoveryItem.setJoinedGuild(entryData.hasJoinedGuild)
    }
}
