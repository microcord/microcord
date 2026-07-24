package com.discord.stores

import com.discord.api.guildmember.GuildMember
import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.models.domain.ModelGuildMemberListUpdate
import com.discord.models.domain.ModelGuildMemberListUpdate.Group
import com.discord.models.domain.ModelGuildMemberListUpdate.Item
import com.discord.models.domain.ModelGuildMemberListUpdate.Operation
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.lazy.memberlist.ChannelMemberList
import com.discord.utilities.lazy.memberlist.MemberListRow
import rx.Observable

class StoreChannelMembers(
    private val observationDeck: ObservationDeck,
    private val getRole: (guildId: Long, roleId: Long) -> GuildRole? = { _, _ -> null },
) : StoreV2() {
    private data class MutableState(
        var groups: List<Group> = emptyList(),
        var onlineCount: Int = 0,
        var memberCount: Int = 0,
        val rows: MutableList<MemberListRow?> = ArrayList(),
    )

    private val memberLists = LinkedHashMap<Long, MutableMap<String, MutableState>>()
    private var memberListsSnapshot: Map<Long, Map<String, ChannelMemberList>> = emptyMap()

    @StoreThread
    fun handleGuildMemberListUpdate(update: ModelGuildMemberListUpdate) {
        var list = memberLists[update.guildId]?.get(update.id)
        update.ops.forEach { operation ->
            if (operation is Operation.Sync && list == null) {
                list = MutableState().also {
                    memberLists.getOrPut(update.guildId) { LinkedHashMap() }[update.id] = it
                }
            }
            list?.apply(operation, update.guildId)
        }
        list?.let {
            it.groups = update.groups.toList()
            it.onlineCount = update.groups.firstOrNull { group -> group.type == Group.Type.ONLINE }?.count ?: 0
            it.memberCount = update.groups.sumOf(Group::count)
            val size = update.groups.sumOf { group -> group.count + 1 }
            while (it.rows.size < size) it.rows.add(null)
            while (it.rows.size > size) it.rows.removeAt(it.rows.lastIndex)
            markChanged()
        }
    }

    @StoreThread
    fun handleGuildRemove(guildId: Long) {
        if (memberLists.remove(guildId) != null) markChanged()
    }

    @StoreThread
    fun handleGuildMemberUpdate(guildId: Long, member: GuildMember) {
        var changed = false
        memberLists[guildId]?.values?.forEach { list ->
            list.rows.indices.forEach { index ->
                val row = list.rows[index]
                if (row is MemberListRow.Member && row.member.user.id == member.user.id) {
                    list.rows[index] = MemberListRow.Member(member.detached())
                    changed = true
                }
            }
        }
        if (changed) markChanged()
    }

    @StoreThread
    fun handleGuildMemberRemove(guildId: Long, userId: Long) {
        var changed = false
        memberLists[guildId]?.values?.forEach { list ->
            list.rows.indices.forEach { index ->
                val row = list.rows[index]
                if (row is MemberListRow.Member && row.member.user.id == userId) {
                    list.rows[index] = null
                    changed = true
                }
            }
        }
        if (changed) markChanged()
    }

    @StoreThread
    fun handleGuildRoleCreateOrUpdate(guildId: Long, role: GuildRole) {
        var changed = false
        memberLists[guildId]?.values?.forEach { list ->
            list.rows.indices.forEach { index ->
                val row = list.rows[index]
                if (row is MemberListRow.GroupHeader && row.group.type == Group.Type.ROLE && row.group.id.toLongOrNull() == role.id) {
                    val updated = row.copy(name = role.name)
                    if (updated != row) {
                        list.rows[index] = updated
                        changed = true
                    }
                }
            }
        }
        if (changed) markChanged()
    }

    @StoreThread
    fun reset() {
        if (memberLists.isEmpty()) return
        memberLists.clear()
        markChanged()
    }

    fun get(guildId: Long, memberListId: String): ChannelMemberList? =
        memberListsSnapshot[guildId]?.get(memberListId)

    fun observe(guildId: Long, memberListId: String): Observable<ChannelMemberList?> =
        observationDeck.connectRx(this) { get(guildId, memberListId) }.distinctUntilChanged()

    override fun snapshotData() {
        memberListsSnapshot = memberLists.mapValues { (guildId, lists) ->
            lists.mapValues { (listId, list) -> list.snapshot(guildId, listId) }
        }
    }

    private fun MutableState.apply(operation: Operation, guildId: Long) {
        when (operation) {
            is Operation.Sync -> {
                ensureSize(operation.range.first + operation.items.size)
                operation.items.forEachIndexed { offset, item -> rows[operation.range.first + offset] = item.row(guildId) }
            }
            is Operation.Update -> if (operation.index in rows.indices) rows[operation.index] = operation.item.row(guildId) else invalidate()
            is Operation.Insert -> if (operation.index in 0..rows.size) rows.add(operation.index, operation.item.row(guildId)) else invalidate()
            is Operation.Delete -> if (operation.index in rows.indices) rows.removeAt(operation.index) else invalidate()
            is Operation.Invalidate -> {
                val end = minOf(operation.range.last, rows.lastIndex)
                if (operation.range.first <= end) for (index in operation.range.first..end) rows[index] = null
            }
        }
    }

    private fun MutableState.ensureSize(size: Int) {
        while (rows.size < size) rows.add(null)
    }

    private fun MutableState.invalidate() {
        rows.indices.forEach { rows[it] = null }
    }

    private fun Item.row(guildId: Long): MemberListRow = when (this) {
        is Item.MemberItem -> MemberListRow.Member(member.detached())
        is Item.GroupItem -> MemberListRow.GroupHeader(
            group,
            group.id.toLongOrNull()?.let { getRole(guildId, it)?.name },
        )
    }

    private fun MutableState.snapshot(guildId: Long, listId: String): ChannelMemberList {
        var index = 0
        val indices = groups.associate { group -> index.also { index += group.count + 1 } to group.id }
        return ChannelMemberList(
            listId = listId,
            groups = groups.toList(),
            groupIndices = indices,
            onlineCount = onlineCount,
            memberCount = memberCount,
            rows = rows.map { row ->
                when (row) {
                    is MemberListRow.Member -> MemberListRow.Member(row.member.detached())
                    is MemberListRow.GroupHeader -> row.copy(group = row.group.copy())
                    null -> null
                }
            },
        )
    }

    private fun GuildMember.detached() = copy(roles = roles.toList(), guildId = guildId)

    companion object {
        fun computeMemberListId(channel: Channel, roles: Map<Long, GuildRole>): String {
            channel.memberListId?.let { return it }
            val everyoneCanView = ((roles[channel.guildId]?.permissions ?: 0L) and Permission.VIEW_CHANNEL) != 0L &&
                channel.permissionOverwrites.orEmpty().none { it.deny and Permission.VIEW_CHANNEL != 0L }
            if (everyoneCanView) return ModelGuildMemberListUpdate.EVERYONE_ID
            val key = channel.permissionOverwrites.orEmpty().mapNotNull { overwrite ->
                when {
                    overwrite.allow and Permission.VIEW_CHANNEL != 0L -> "allow:${overwrite.id}"
                    overwrite.deny and Permission.VIEW_CHANNEL != 0L -> "deny:${overwrite.id}"
                    else -> null
                }
            }.sorted().joinToString(",")
            return if (key.isEmpty()) "" else murmur3(key).toUInt().toString()
        }

        private fun murmur3(value: String): Int {
            val bytes = value.toByteArray(Charsets.UTF_8)
            var hash = 0
            var index = 0
            while (index + 4 <= bytes.size) {
                var chunk = (bytes[index].toInt() and 0xff) or ((bytes[index + 1].toInt() and 0xff) shl 8) or
                    ((bytes[index + 2].toInt() and 0xff) shl 16) or ((bytes[index + 3].toInt() and 0xff) shl 24)
                chunk *= -862048943
                chunk = Integer.rotateLeft(chunk, 15) * 461845907
                hash = Integer.rotateLeft(hash xor chunk, 13) * 5 - 430675100
                index += 4
            }
            var tail = 0
            for (offset in bytes.lastIndex downTo index) tail = (tail shl 8) or (bytes[offset].toInt() and 0xff)
            if (index < bytes.size) {
                tail *= -862048943
                hash = hash xor (Integer.rotateLeft(tail, 15) * 461845907)
            }
            hash = hash xor bytes.size
            hash = (hash xor (hash ushr 16)) * -2048144789
            hash = (hash xor (hash ushr 13)) * -1028477387
            return hash xor (hash ushr 16)
        }
    }
}
