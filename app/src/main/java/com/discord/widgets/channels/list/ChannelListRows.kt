package com.discord.widgets.channels.list

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission

sealed class ChannelListRow(
    open val channel: Channel,
    open val unread: Boolean,
    open val mentionCount: Int,
    open val selected: Boolean,
) {
    val stableId: Long get() = channel.id
    val name: String get() = channel.name ?: channel.recipients.orEmpty().joinToString(", ") { it.username }

    data class Category(override val channel: Channel, override val unread: Boolean = false, override val mentionCount: Int = 0) :
        ChannelListRow(channel, unread, mentionCount, false)
    data class Text(override val channel: Channel, override val unread: Boolean = false, override val mentionCount: Int = 0, override val selected: Boolean = false) :
        ChannelListRow(channel, unread, mentionCount, selected)
    data class Voice(override val channel: Channel, override val unread: Boolean = false, override val mentionCount: Int = 0, override val selected: Boolean = false) :
        ChannelListRow(channel, unread, mentionCount, selected)
    data class Stage(override val channel: Channel, override val unread: Boolean = false, override val mentionCount: Int = 0, override val selected: Boolean = false) :
        ChannelListRow(channel, unread, mentionCount, selected)
    data class Directory(override val channel: Channel, override val unread: Boolean = false, override val mentionCount: Int = 0, override val selected: Boolean = false) :
        ChannelListRow(channel, unread, mentionCount, selected)
    data class Thread(override val channel: Channel, override val unread: Boolean = false, override val mentionCount: Int = 0, override val selected: Boolean = false) :
        ChannelListRow(channel, unread, mentionCount, selected)
    data class Private(override val channel: Channel, override val unread: Boolean = false, override val mentionCount: Int = 0, override val selected: Boolean = false) :
        ChannelListRow(channel, unread, mentionCount, selected)
}

object ChannelListRows {
    const val VIEW_CHANNEL = Permission.VIEW_CHANNEL
    const val CONNECT = Permission.CONNECT

    private val order = compareBy<Channel> { it.position }.thenBy { it.id }

    fun guild(
        channels: Collection<Channel>,
        permissions: Map<Long, Long>,
        unreadChannelIds: Set<Long> = emptySet(),
        mentionCounts: Map<Long, Int> = emptyMap(),
        selectedChannelId: Long = 0,
        collapsedCategoryIds: Set<Long> = emptySet(),
    ): List<ChannelListRow> {
        val categories = channels.filter { it.type == Channel.CATEGORY }.sortedWith(order)
        val categoryIds = categories.mapTo(HashSet(), Channel::id)
        val visible = channels.filter { channel ->
            channel.type != Channel.CATEGORY && row(channel, unreadChannelIds, mentionCounts, selectedChannelId) != null &&
                permissions[channel.id].orZero() and VIEW_CHANNEL != 0L &&
                (channel.type !in setOf(Channel.GUILD_VOICE, Channel.GUILD_STAGE_VOICE) ||
                    permissions[channel.id].orZero() and CONNECT != 0L)
        }
        fun categoryId(channel: Channel): Long? {
            var parentId = channel.parentId
            repeat(2) {
                if (parentId in categoryIds) return parentId
                parentId = channels.firstOrNull { it.id == parentId }?.parentId ?: return null
            }
            return null
        }
        data class Section(val anchor: Channel, val children: List<Channel>)
        val sections = visible.filter { categoryId(it) == null }.map { Section(it, listOf(it)) }.toMutableList()
        categories.forEach { category ->
            val children = visible.filter { categoryId(it) == category.id }.sortedWith(order)
            if (children.isNotEmpty() || category.id in collapsedCategoryIds) {
                sections += Section(category, children)
            }
        }
        return sections.sortedWith(compareBy<Section> { it.anchor.position }.thenBy { it.anchor.id }).flatMap { section ->
            if (section.anchor.type != Channel.CATEGORY) {
                listOfNotNull(row(section.anchor, unreadChannelIds, mentionCounts, selectedChannelId))
            } else {
                val displayed = if (section.anchor.id in collapsedCategoryIds) {
                    section.children.filter { it.id == selectedChannelId || it.id in unreadChannelIds || (mentionCounts[it.id] ?: 0) > 0 }
                } else section.children
                listOf(ChannelListRow.Category(
                    section.anchor,
                    section.children.any { it.id in unreadChannelIds },
                    section.children.sumOf { mentionCounts[it.id] ?: 0 },
                )) + displayed.mapNotNull { row(it, unreadChannelIds, mentionCounts, selectedChannelId) }
            }
        }
    }

    fun private(
        channels: Collection<Channel>,
        unreadChannelIds: Set<Long> = emptySet(),
        mentionCounts: Map<Long, Int> = emptyMap(),
        selectedChannelId: Long = 0,
    ): List<ChannelListRow.Private> = channels
        .filter { it.type == Channel.DM || it.type == Channel.GROUP_DM }
        .sortedWith(compareByDescending<Channel> { it.lastMessageId }.thenBy { it.id })
        .map { ChannelListRow.Private(it, it.id in unreadChannelIds, mentionCounts[it.id] ?: 0, it.id == selectedChannelId) }

    private fun row(channel: Channel, unreadIds: Set<Long> = emptySet(), mentions: Map<Long, Int> = emptyMap(), selectedId: Long = 0): ChannelListRow? = when (channel.type) {
        Channel.GUILD_TEXT, Channel.GUILD_ANNOUNCEMENT -> ChannelListRow.Text(channel, channel.id in unreadIds, mentions[channel.id] ?: 0, channel.id == selectedId)
        Channel.GUILD_VOICE -> ChannelListRow.Voice(channel, channel.id in unreadIds, mentions[channel.id] ?: 0, channel.id == selectedId)
        Channel.GUILD_STAGE_VOICE -> ChannelListRow.Stage(channel, channel.id in unreadIds, mentions[channel.id] ?: 0, channel.id == selectedId)
        Channel.DIRECTORY -> ChannelListRow.Directory(channel, channel.id in unreadIds, mentions[channel.id] ?: 0, channel.id == selectedId)
        in Channel.ANNOUNCEMENT_THREAD..Channel.PRIVATE_THREAD -> ChannelListRow.Thread(channel, channel.id in unreadIds, mentions[channel.id] ?: 0, channel.id == selectedId)
        else -> null
    }

    private fun Long?.orZero() = this ?: 0L
}
