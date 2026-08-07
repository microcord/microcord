package com.discord.widgets.channels.memberlist

import com.discord.api.channel.Channel
import com.discord.models.domain.ModelApplicationStream
import com.discord.models.presence.Presence
import com.discord.models.user.User

fun generateGroupDmMemberListItems(
    channel: Channel,
    users: Map<Long, User>,
    presences: Map<Long, Presence>,
    applicationStreams: Map<Long, ModelApplicationStream>,
    canInvite: Boolean,
    currentUser: User?,
): WidgetChannelMembersListViewModel.MemberList {
    val items = ChannelMemberListRows.privateChannel(
        channel,
        users,
        presences,
        canAddMembers = canInvite,
        currentUser = currentUser,
        streamingUserIds = applicationStreams.keys,
    ).map(ChannelMemberListRow::toAdapterItem)
    return PrivateChannelMemberListItems(channel.id.toString(), items)
}
