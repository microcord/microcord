package com.discord.widgets.channels.memberlist

import com.discord.api.channel.Channel
import com.discord.api.presence.ClientStatus
import com.discord.api.role.GuildRole
import com.discord.api.user.Nameplate
import com.discord.models.domain.ModelGuildMemberListUpdate.Group
import com.discord.models.member.GuildMember
import com.discord.models.presence.Presence
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.utilities.lazy.memberlist.ChannelMemberList
import com.discord.utilities.lazy.memberlist.MemberListRow
import com.discord.utilities.user.UserUtils.getDisplayName
import java.util.Locale

sealed class ChannelMemberListRow(open val key: String) {
    val stableId: Long get() = key.fold(-3750763034362895579L) { hash, char -> (hash xor char.code.toLong()) * 1099511628211L }

    data class Header(
        override val key: String,
        val kind: Kind,
        val count: Int,
    ) : ChannelMemberListRow(key) {
        enum class Kind { ONLINE, OFFLINE, MEMBERS }
    }

    data class RoleHeader(
        override val key: String,
        val role: GuildRole,
        val count: Int,
    ) : ChannelMemberListRow(key)

    data class Member(
        val user: User,
        val guildMember: GuildMember?,
        val presence: Presence?,
        val displayName: String,
        val isOwner: Boolean,
        val isApplicationStreaming: Boolean = false,
        val canDisplayStatusEmoji: Boolean = true,
        val nameplate: Nameplate? = guildMember?.collectibles?.nameplate ?: user.collectibles?.nameplate,
    ) : ChannelMemberListRow("member:${user.id}")

    data class PlaceholderHeader(override val key: String) : ChannelMemberListRow(key)
    data class PlaceholderMember(override val key: String) : ChannelMemberListRow(key)
    data class AddMember(override val key: String, val title: Int) : ChannelMemberListRow(key)
    data class JoinLeaveThread(override val key: String, val joined: Boolean) : ChannelMemberListRow(key)
}

sealed class LazyMemberListRow(open val key: String) {
    data class Header(
        override val key: String,
        val kind: ChannelMemberListRow.Header.Kind,
        val count: Int,
        val role: GuildRole? = null,
    ) : LazyMemberListRow(key)

    data class Member(val userId: Long) : LazyMemberListRow("member:$userId")
    data class PlaceholderHeader(override val key: String) : LazyMemberListRow(key)
    data class PlaceholderMember(override val key: String) : LazyMemberListRow(key)
}

object ChannelMemberListRows {
    data class GuildInput(
        val listId: String,
        val ownerId: Long,
        val rows: List<LazyMemberListRow>,
        val users: Map<Long, User>,
        val members: Map<Long, GuildMember>,
        val presences: Map<Long, Presence>,
        val streamingUserIds: Set<Long> = emptySet(),
    )

    fun loading(listId: String, memberCount: Int = 12): List<ChannelMemberListRow> = buildList {
        add(ChannelMemberListRow.PlaceholderHeader("$listId:placeholder-header"))
        repeat(memberCount) { add(ChannelMemberListRow.PlaceholderMember("$listId:placeholder-member:$it")) }
    }

    fun guild(input: GuildInput): List<ChannelMemberListRow> = input.rows.map { row ->
        when (row) {
            is LazyMemberListRow.Header -> row.role?.let {
                ChannelMemberListRow.RoleHeader("${input.listId}:header:${row.key}", it, row.count)
            } ?: ChannelMemberListRow.Header("${input.listId}:header:${row.key}", row.kind, row.count)
            is LazyMemberListRow.Member -> member(
                input.listId,
                row.userId,
                input.users,
                input.members,
                input.presences,
                input.ownerId,
                input.streamingUserIds,
            )
            is LazyMemberListRow.PlaceholderHeader ->
                ChannelMemberListRow.PlaceholderHeader("${input.listId}:placeholder-header:${row.key}")
            is LazyMemberListRow.PlaceholderMember ->
                ChannelMemberListRow.PlaceholderMember("${input.listId}:placeholder-member:${row.key}")
        }
    }

    fun guild(
        list: ChannelMemberList,
        guildId: Long,
        ownerId: Long,
        users: Map<Long, User>,
        members: Map<Long, GuildMember>,
        presences: Map<Long, Presence>,
        roles: Map<Long, GuildRole>,
        streamingUserIds: Set<Long> = emptySet(),
    ): List<ChannelMemberListRow> {
        val lazyUsers = users.toMutableMap()
        val lazyMembers = members.toMutableMap()
        val rows = list.rows.mapIndexed { index, row ->
            when (row) {
                is MemberListRow.GroupHeader -> LazyMemberListRow.Header(
                    row.group.id,
                    when (row.group.type) {
                        Group.Type.ONLINE -> ChannelMemberListRow.Header.Kind.ONLINE
                        Group.Type.OFFLINE -> ChannelMemberListRow.Header.Kind.OFFLINE
                        Group.Type.ROLE -> ChannelMemberListRow.Header.Kind.ONLINE
                    },
                    row.group.count,
                    row.group.id.toLongOrNull()?.let(roles::get),
                )
                is MemberListRow.Member -> {
                    val apiMember = row.member
                    if (apiMember.user.id !in lazyUsers) lazyUsers[apiMember.user.id] = CoreUser(apiMember.user)
                    if (apiMember.user.id !in lazyMembers) lazyMembers[apiMember.user.id] = GuildMember.from(apiMember, guildId, roles)
                    LazyMemberListRow.Member(apiMember.user.id)
                }
                null -> if (index in list.groupIndices) {
                    LazyMemberListRow.PlaceholderHeader(index.toString())
                } else {
                    LazyMemberListRow.PlaceholderMember(index.toString())
                }
            }
        }
        return guild(GuildInput(list.listId, ownerId, rows, lazyUsers, lazyMembers, presences, streamingUserIds))
    }

    fun loadedGuild(
        listId: String,
        ownerId: Long,
        users: Map<Long, User>,
        members: Map<Long, GuildMember>,
        presences: Map<Long, Presence>,
        roles: Map<Long, GuildRole>,
        streamingUserIds: Set<Long> = emptySet(),
    ): List<ChannelMemberListRow> = guild(
        GuildInput(listId, ownerId, groupedRows(users, members, presences, roles), users, members, presences, streamingUserIds),
    )

    fun thread(
        listId: String,
        ownerId: Long,
        memberIds: Set<Long>,
        users: Map<Long, User>,
        members: Map<Long, GuildMember>,
        presences: Map<Long, Presence>,
        roles: Map<Long, GuildRole>,
        joined: Boolean? = null,
        streamingUserIds: Set<Long> = emptySet(),
    ): List<ChannelMemberListRow> {
        val filteredUsers = users.filterKeys(memberIds::contains)
        return buildList {
            if (joined != null) add(ChannelMemberListRow.JoinLeaveThread("$listId:join-leave", joined))
            addAll(loadedGuild(listId, ownerId, filteredUsers, members, presences, roles, streamingUserIds))
        }
    }

    fun privateChannel(
        channel: Channel,
        users: Map<Long, User>,
        presences: Map<Long, Presence>,
        canAddMembers: Boolean = false,
        currentUser: User? = null,
        streamingUserIds: Set<Long> = emptySet(),
    ): List<ChannelMemberListRow> {
        val resolvedUsers = users.toMutableMap()
        channel.recipients.orEmpty().forEach { if (it.id !in resolvedUsers) resolvedUsers[it.id] = CoreUser(it) }
        currentUser?.let { if (it.id !in resolvedUsers) resolvedUsers[it.id] = it }
        val ids = (channel.recipientIds.orEmpty() + channel.recipients.orEmpty().map { it.id } + listOfNotNull(currentUser?.id)).toSet()
        val nicks = channel.nicks.orEmpty().associate { it.id to it.nick }
        val recipients = resolvedUsers.values.filter { it.id in ids }.sortedWith(
            compareBy<User> { it.getDisplayName().lowercase(Locale.ROOT) }
                .thenBy { it.id },
        )
        return buildList {
            if (canAddMembers) add(ChannelMemberListRow.AddMember("${channel.id}:add", com.discord.R.string.invite_members))
            add(ChannelMemberListRow.Header("${channel.id}:members", ChannelMemberListRow.Header.Kind.MEMBERS, recipients.size))
            recipients.forEach { user ->
                add(ChannelMemberListRow.Member(
                    user,
                    null,
                    presences[user.id],
                    user.getDisplayName(nicks[user.id]),
                    user.id == channel.ownerId,
                    user.id in streamingUserIds,
                ))
            }
        }
    }

    private fun groupedRows(
        users: Map<Long, User>,
        members: Map<Long, GuildMember>,
        presences: Map<Long, Presence>,
        roles: Map<Long, GuildRole>,
    ): List<LazyMemberListRow> {
        val orderedRoles = roles.values.filter(GuildRole::hoist)
            .sortedWith(compareByDescending<GuildRole> { it.position }.thenByDescending { it.id })
        val onlineByRole = orderedRoles.associate { it.id to mutableListOf<Long>() }.toMutableMap()
        val online = mutableListOf<Long>()
        val offline = mutableListOf<Long>()
        users.keys.forEach { userId ->
            val status = presences[userId]?.status
            if (status == null || status == ClientStatus.OFFLINE || status == ClientStatus.INVISIBLE) {
                offline += userId
            } else {
                val roleId = members[userId]?.hoistRoleId
                onlineByRole[roleId]?.add(userId) ?: online.add(userId)
            }
        }
        val order = compareBy<Long> { userId ->
            users.getValue(userId).getDisplayName(members[userId]?.nick).lowercase(Locale.ROOT)
        }.thenBy { it }
        return buildList {
            orderedRoles.forEach { role ->
                val ids = onlineByRole.getValue(role.id).sortedWith(order)
                if (ids.isNotEmpty()) {
                    add(LazyMemberListRow.Header("role:${role.id}", ChannelMemberListRow.Header.Kind.ONLINE, ids.size, role))
                    addAll(ids.map(LazyMemberListRow::Member))
                }
            }
            if (online.isNotEmpty()) {
                add(LazyMemberListRow.Header("online", ChannelMemberListRow.Header.Kind.ONLINE, online.size))
                addAll(online.sortedWith(order).map(LazyMemberListRow::Member))
            }
            if (offline.isNotEmpty()) {
                add(LazyMemberListRow.Header("offline", ChannelMemberListRow.Header.Kind.OFFLINE, offline.size))
                addAll(offline.sortedWith(order).map(LazyMemberListRow::Member))
            }
        }
    }

    private fun member(
        listId: String,
        userId: Long,
        users: Map<Long, User>,
        members: Map<Long, GuildMember>,
        presences: Map<Long, Presence>,
        ownerId: Long,
        streamingUserIds: Set<Long>,
    ): ChannelMemberListRow = users[userId]?.let { user ->
        val guildMember = members[userId]
        ChannelMemberListRow.Member(
            user,
            guildMember,
            presences[userId],
            user.getDisplayName(guildMember?.nick),
            userId == ownerId,
            userId in streamingUserIds,
        )
    } ?: ChannelMemberListRow.PlaceholderMember("$listId:missing:$userId")
}
