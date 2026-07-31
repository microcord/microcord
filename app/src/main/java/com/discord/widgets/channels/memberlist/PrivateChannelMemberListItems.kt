package com.discord.widgets.channels.memberlist

import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter

internal data class PrivateChannelMemberListItems(
    override val listId: String,
    private val listItems: List<ChannelMembersListAdapter.Item>,
) : WidgetChannelMembersListViewModel.MemberList {
    override val size get() = listItems.size
    override fun get(index: Int) = listItems[index]
    override fun getHeaderPositionForItem(itemPosition: Int) = 0
}
