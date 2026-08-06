package com.discord.widgets.channels.memberlist

import com.discord.api.channel.Channel
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.stores.StoreStream
import com.discord.api.role.GuildRole
import com.discord.models.domain.ModelApplicationStream
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.stores.StoreEmojiCustom
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreThreadsJoined

fun generateThreadMemberListItems(
    channel: Channel,
    roles: Map<Long, GuildRole>,
    guild: Guild?,
    guildMembers: Map<Long, GuildMember>,
    users: Map<Long, User>,
    presences: Map<Long, Presence>,
    streams: Map<Long, ModelApplicationStream>,
    threadMembers: Set<Long>,
    joinedThread: StoreThreadsJoined.JoinedThread?,
    storeGuilds: StoreGuilds,
    storeEmojiCustom: StoreEmojiCustom,
): WidgetChannelMembersListViewModel.MemberList {
    val memberIds = threadMembers.ifEmpty { channel.memberIdsPreview.orEmpty().toSet() }
    val items = ChannelMemberListRows.thread(
        channel.id.toString(),
        guild?.ownerId ?: 0L,
        memberIds,
        users,
        guildMembers,
        presences,
        roles,
        joined = joinedThread != null,
        isForumPost = StoreStream.getChannels().getChannel(channel.parentId)?.isThreadOnlyChannel() == true,
        streamingUserIds = streams.keys,
    ).map { it.toAdapterItem(channel.guildId) }.toMutableList()
    return ThreadMemberListItems(channel.id.toString(), items)
}
