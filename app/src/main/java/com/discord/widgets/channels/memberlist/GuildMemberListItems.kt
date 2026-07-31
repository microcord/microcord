package com.discord.widgets.channels.memberlist

import com.discord.utilities.collections.SparseMutableList
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter
import java.util.SortedMap

internal data class GuildMemberListItems(
    override val listId: String,
    override val size: Int,
    private val listItems: SparseMutableList<ChannelMembersListAdapter.Item>,
    private val groupIndices: SortedMap<Int, String>,
) : WidgetChannelMembersListViewModel.MemberList {
    override fun get(index: Int): ChannelMembersListAdapter.Item =
        listItems[index] ?: PLACEHOLDER_INSTANCES[index % PLACEHOLDER_INSTANCES.size]

    override fun getHeaderPositionForItem(itemPosition: Int): Int? =
        groupIndices.keys.lastOrNull { it <= itemPosition }
}

internal val PLACEHOLDER_INSTANCES = listOf(0.7f, 0.3f, 0.6f, 0.4f, 0.6f, 0.8f, 0.3f, 0.5f, 0.7f, 0.4f)
    .map(ChannelMembersListAdapter.Item::PlaceholderMember)
