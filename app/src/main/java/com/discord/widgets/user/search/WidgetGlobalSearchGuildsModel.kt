package com.discord.widgets.user.search

import com.discord.api.channel.Channel
import com.discord.models.guild.Guild
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

/**
 * The horizontal rail above the quick-switcher results.
 *
 * It is a shortcut list, not a filter: recent DMs first, then a divider, then the servers ordered by
 * unread mentions. The single most recent guild is dropped — it is the one already on screen behind
 * the switcher — which is why the guild sequence skips its first element.
 */
data class WidgetGlobalSearchGuildsModel(
    val items: List<Item>,
    val hasChannels: Boolean,
) {

    /**
     * One rail entry. The three kinds share a class because they share a slot: same size, same
     * unread dot, same selection ring.
     */
    data class Item(
        override val type: Int,
        val id: Long,
        val channel: Channel? = null,
        val guild: Guild? = null,
        val mentionCount: Int = 0,
        val isSelected: Boolean = false,
        val isUnread: Boolean = false,
        val connectedToVoice: Boolean = false,
        val unavailableGuildCount: Int = 0,
    ) : MGRecyclerDataPayload {
        override val key: String = "$type-$id"

        companion object {
            const val TYPE_DIVIDER = 1
            const val TYPE_DM = 2
            const val TYPE_GUILD = 3

            fun createDirectMessage(channel: Channel, mentionCount: Int): Item =
                Item(TYPE_DM, channel.id, channel = channel, mentionCount = mentionCount)

            /** Id 1 rather than 0: the key has to be stable and distinct from any real snowflake. */
            fun createDivider(): Item = Item(TYPE_DIVIDER, 1L)

            fun createGuild(
                guild: Guild,
                mentionCount: Int,
                isSelected: Boolean,
                isUnread: Boolean,
                connectedToVoice: Boolean,
            ): Item = Item(
                TYPE_GUILD,
                guild.id,
                guild = guild,
                mentionCount = mentionCount,
                isSelected = isSelected,
                isUnread = isUnread,
                connectedToVoice = connectedToVoice,
            )
        }
    }

    companion object {
        fun create(
            recentGuildIds: List<Long>,
            selectedGuildId: Long,
            unreadGuildIds: Set<Long>,
            mentionCounts: Map<Long, Int>,
            privateChannels: Map<Long, Channel>,
            guilds: Map<Long, Guild>,
            channelIdsByGuild: Map<Long, List<Long>>,
        ): WidgetGlobalSearchGuildsModel {
            val dmItems = privateChannels.values
                .map { Item.createDirectMessage(it, mentionCounts[it.id] ?: 0) }

            // The guild currently behind the switcher is already reachable; the rail is for the
            // ones that are not.
            val guildItems = (recentGuildIds + guilds.keys)
                .distinct()
                .drop(1)
                .mapNotNull { guilds[it] }
                .map { guild ->
                    Item.createGuild(
                        guild = guild,
                        mentionCount = mentionCounts[guild.id] ?: 0,
                        isSelected = guild.id == selectedGuildId,
                        isUnread = guild.id in unreadGuildIds,
                        connectedToVoice = false,
                    )
                }
                .sortedByDescending(Item::mentionCount)

            val items = when {
                dmItems.isEmpty() -> guildItems
                else -> dmItems + Item.createDivider() + guildItems
            }
            return WidgetGlobalSearchGuildsModel(items, channelIdsByGuild.isNotEmpty())
        }
    }
}
