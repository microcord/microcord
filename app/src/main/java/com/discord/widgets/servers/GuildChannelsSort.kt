package com.discord.widgets.servers

import com.discord.api.channel.Channel
import com.discord.api.channel.getSortByNameAndType
import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.channel.isCategory
import com.discord.utilities.mg_recycler.CategoricalDragAndDropAdapter
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

    data class PositionUpdate(val position: Int, val parentId: Long?)

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

    fun changedPositions(
        original: List<CategoricalDragAndDropAdapter.Payload>,
        reordered: List<CategoricalDragAndDropAdapter.Payload>,
    ): Map<Long, PositionUpdate> {
        val originalPositions = original.associate { item -> item.key to item.position }
        val updates = LinkedHashMap<Long, PositionUpdate>()
        var parentId: Long? = null

        reordered.forEachIndexed { index, item ->
            if (item is SettingsChannelListAdapter.CategoryItem) parentId = item.id.takeIf { it > 0L }
            if (originalPositions[item.key] == index || !item.canBeManaged()) return@forEachIndexed

            val id = item.idOrNull() ?: return@forEachIndexed
            val changedParent = (item as? SettingsChannelListAdapter.ChannelItem)?.let { channel ->
                val oldParent = channel.parentId.takeIf { it > 0L }
                if (oldParent == parentId) null else parentId ?: NO_CATEGORY
            }
            updates[id] = PositionUpdate(index, changedParent)
        }
        return updates
    }

    fun applyPositions(
        channels: Map<Long, Channel>,
        updates: Map<Long, PositionUpdate>,
    ): Map<Long, Channel> = channels.mapValues { (id, channel) ->
        updates[id]?.let { update ->
            channel.copy(
                position = update.position,
                parentId = update.parentId?.let { if (it == NO_CATEGORY) 0L else it } ?: channel.parentId,
            )
        } ?: channel
    }

    fun sortedRows(
        rows: List<CategoricalDragAndDropAdapter.Payload>,
    ): List<CategoricalDragAndDropAdapter.Payload> {
        val categories = rows.filterIsInstance<SettingsChannelListAdapter.CategoryItem>()
            .sortedWith(compareBy<SettingsChannelListAdapter.CategoryItem> { if (it.id == 0L) 0 else 1 }
                .thenBy { it.position }
                .thenBy { it.id })
        val channels = rows.filterIsInstance<SettingsChannelListAdapter.ChannelItem>()
        return categories.flatMap { category ->
            listOf<CategoricalDragAndDropAdapter.Payload>(category) + channels
                .filter { it.parentId == category.id }
                .sortedWith(compareBy<SettingsChannelListAdapter.ChannelItem> { if (it.channel.isGuildTextyChannel()) 0 else 1 }
                    .thenBy { it.channel.position }
                    .thenBy { it.channel.id })
        }
    }

    private fun CategoricalDragAndDropAdapter.Payload.idOrNull(): Long? = when (this) {
        is SettingsChannelListAdapter.ChannelItem -> channel.id
        is SettingsChannelListAdapter.CategoryItem -> id
        else -> null
    }

    private fun CategoricalDragAndDropAdapter.Payload.canBeManaged(): Boolean = when (this) {
        is SettingsChannelListAdapter.ChannelItem -> canManageCategoryOfChannel
        is SettingsChannelListAdapter.CategoryItem -> canManageCategory
        else -> false
    }

    const val NO_CATEGORY = -1L
}

internal class ChannelOrderWriteState {
    private data class Pending(
        val revision: Long,
        val target: Map<Long, Pair<Int, Long>>,
    )

    private var revision = 0L
    private val pending = ArrayList<Pending>()

    fun begin(
        channels: Map<Long, Channel>,
        updates: Map<Long, GuildChannelsSort.PositionUpdate>,
    ): Long {
        val nextRevision = ++revision
        val updated = GuildChannelsSort.applyPositions(channels, updates)
        pending += Pending(nextRevision, signature(updated))
        return nextRevision
    }

    fun success(revision: Long) = Unit

    fun failure(revision: Long): Boolean {
        val index = pending.indexOfFirst { it.revision == revision }
        if (index < 0) return false
        val changedVisibleTarget = index == pending.lastIndex
        pending.removeAt(index)
        return changedVisibleTarget
    }

    fun apply(channels: Map<Long, Channel>): Map<Long, Channel> {
        val authoritative = signature(channels)
        val matched = pending.indexOfLast { mutation ->
            mutation.target.all { (id, order) -> authoritative[id] == order }
        }
        if (matched >= 0) pending.subList(0, matched + 1).clear()
        val target = pending.lastOrNull()?.target ?: return channels
        return channels.mapValues { (id, channel) ->
            target[id]?.let { (position, parentId) ->
                channel.copy(position = position, parentId = parentId)
            } ?: channel
        }
    }

    private fun signature(channels: Map<Long, Channel>): Map<Long, Pair<Int, Long>> =
        channels.mapValues { (_, channel) -> channel.position to channel.parentId }
}
