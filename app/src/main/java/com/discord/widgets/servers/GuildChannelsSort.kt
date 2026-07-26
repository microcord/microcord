package com.discord.widgets.servers

import com.discord.api.channel.Channel
import com.discord.api.channel.getSortByNameAndType
import com.discord.api.channel.isCategory
import com.discord.utilities.permissions.PermissionUtils
import java.util.TreeMap
import java.util.TreeSet

/**
 * Groups a guild's channels under their categories, in the order the sidebar shows them.
 *
 * This is `com.discord.utilities.channel.GuildChannelsInfo.getSortedCategories` /
 * `getSortedVisibleChannels`. `GuildChannelsInfo` itself has not been reconstructed — it combines
 * eight stores, three of which (`StoreGuilds.observeRoles` aside) are needed only for the
 * server-settings screens this slice does not build yet — so the two sorting members the
 * notification screens actually use live here until it lands. Move them then; nothing else in this
 * package depends on their location.
 */
internal object GuildChannelsSort {

    /**
     * Categories in display order, each mapped to its channels in display order.
     *
     * A channel with no category is grouped under its `parentId` of `0`, which sorts first because
     * the comparator puts a missing (`null`) category channel ahead of every real one.
     */
    fun sortedCategories(guildChannels: Map<Long, Channel?>): Map<Long, Collection<Channel>> {
        val comparator = Channel.getSortByNameAndType()
        val categories = TreeMap<Long, TreeSet<Channel>> { first, second ->
            comparator.compare(guildChannels[first], guildChannels[second])
        }
        for (channel in guildChannels.values.filterNotNull()) {
            val categoryId = if (channel.isCategory()) channel.id else channel.parentId
            categories.getOrPut(categoryId) { TreeSet(comparator) }.add(channel)
        }
        return categories
    }

    /**
     * [sortedCategories] flattened to a single list, keeping only channels the user can see.
     *
     * A category whose surviving group has exactly one entry contributes nothing: that entry is the
     * category header itself, with every channel under it hidden. Reproduced from the reference,
     * which drops such a group wholesale rather than keeping the empty header.
     */
    fun sortedVisibleChannels(
        guildChannels: Map<Long, Channel>,
        channelPermissions: Map<Long, Long>,
    ): List<Channel> = sortedCategories(guildChannels).values.flatMap { group ->
        val visible = group.filter { PermissionUtils.hasAccess(it, channelPermissions[it.id]) }
        if (visible.size == 1) emptyList() else visible
    }
}
