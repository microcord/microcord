package com.discord.widgets.channels.memberlist

import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.role.GuildRole
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.stores.StoreEmojiCustom
import com.discord.stores.StoreGuilds
import com.discord.utilities.collections.SparseMutableList
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.lazy.memberlist.ChannelMemberList
import com.discord.utilities.lazy.memberlist.MemberListRow
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter
import java.util.TreeMap

fun generateGuildMemberListItems(
    channelMemberList: ChannelMemberList,
    guild: Guild?,
    channel: Channel,
    guildRoles: Map<Long, GuildRole>,
    canInvite: Boolean,
    isThreadJoined: Boolean,
    storeGuilds: StoreGuilds,
    storeCustomEmojis: StoreEmojiCustom,
    users: Map<Long, User> = emptyMap(),
    guildMembers: Map<Long, GuildMember> = emptyMap(),
    presences: Map<Long, Presence> = emptyMap(),
    streamingUserIds: Set<Long> = emptySet(),
): WidgetChannelMembersListViewModel.MemberList {
    val rows = ChannelMemberListRows.guild(
        channelMemberList,
        channel.guildId,
        guild?.ownerId ?: 0L,
        users,
        guildMembers,
        presences,
        guildRoles,
        streamingUserIds,
    )
    val groupIndices = TreeMap(channelMemberList.groupIndices)

    val leadingItem = when {
        canInvite -> ChannelMembersListAdapter.Item.AddMember(channelMemberList.listId, R.string.invite_members)
        channel.type in THREAD_TYPES -> ChannelMembersListAdapter.Item.JoinLeaveThread(channelMemberList.listId, isThreadJoined)
        else -> null
    }
    val offset = if (leadingItem == null) 0 else 1
    if (leadingItem != null) {
        val shifted = TreeMap<Int, String>()
        groupIndices.forEach { (index, id) -> shifted[index + 1] = id }
        groupIndices.clear()
        groupIndices.putAll(shifted)
    }

    val sparse = SparseMutableList<ChannelMembersListAdapter.Item>(rows.size + offset)
    leadingItem?.let { sparse[0] = it }
    rows.forEachIndexed { index, row ->
        if (row is ChannelMemberListRow.PlaceholderMember) return@forEachIndexed
        val source = channelMemberList.rows.getOrNull(index)
        sparse[index + offset] = if (source is MemberListRow.GroupHeader && source.group.type == com.discord.models.domain.ModelGuildMemberListUpdate.Group.Type.ROLE) {
            val roleId = source.group.id.toLongOrNull() ?: 0L
            ChannelMembersListAdapter.Item.RoleHeader(
                roleId,
                guildRoles[roleId]?.name.orEmpty(),
                source.group.count,
                channel.guildId,
            )
        } else {
            row.toAdapterItem()
        }
    }
    return GuildMemberListItems(channelMemberList.listId, rows.size + offset, sparse, groupIndices)
}

internal fun ChannelMemberListRow.toAdapterItem(): ChannelMembersListAdapter.Item = when (this) {
    is ChannelMemberListRow.Header -> ChannelMembersListAdapter.Item.Header(
        key,
        when (kind) {
            ChannelMemberListRow.Header.Kind.ONLINE -> ChannelMembersListAdapter.Item.Header.Type.ONLINE
            ChannelMemberListRow.Header.Kind.OFFLINE -> ChannelMembersListAdapter.Item.Header.Type.OFFLINE
            ChannelMemberListRow.Header.Kind.MEMBERS -> ChannelMembersListAdapter.Item.Header.Type.GROUP_DM
        },
        count,
    )
    is ChannelMemberListRow.RoleHeader -> ChannelMembersListAdapter.Item.RoleHeader(role.id, role.name, count, null)
    is ChannelMemberListRow.Member -> ChannelMembersListAdapter.Item.Member(
        user.id,
        guildMember?.guildId,
        displayName,
        user.isBot,
        if (user.isSystemUser) R.string.system_dm_tag_system else R.string.bot_tag_bot,
        user.publicFlags and VERIFIED_BOT_FLAG != 0,
        presence,
        guildMember?.color,
        IconUtils.getForUser(user),
        isOwner,
        guildMember?.premiumSince,
        isApplicationStreaming,
        canDisplayStatusEmoji,
        user.flags or user.publicFlags,
    )
    is ChannelMemberListRow.PlaceholderHeader -> ChannelMembersListAdapter.Item.PlaceholderHeader(key)
    is ChannelMemberListRow.PlaceholderMember -> PLACEHOLDER_INSTANCES[(key.hashCode() and Int.MAX_VALUE) % PLACEHOLDER_INSTANCES.size]
    is ChannelMemberListRow.AddMember -> ChannelMembersListAdapter.Item.AddMember(key, title)
    is ChannelMemberListRow.JoinLeaveThread -> ChannelMembersListAdapter.Item.JoinLeaveThread(key, joined)
}

private val THREAD_TYPES = setOf(Channel.ANNOUNCEMENT_THREAD, Channel.PUBLIC_THREAD, Channel.PRIVATE_THREAD)
private const val VERIFIED_BOT_FLAG = 1 shl 16
