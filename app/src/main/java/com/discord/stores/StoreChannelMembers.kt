package com.discord.stores

import android.util.Log
import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.role.GuildRole
import com.discord.api.guildmember.GuildMember
import com.discord.models.domain.ModelGuildMemberListUpdate
import com.discord.models.domain.ModelGuildMemberListUpdate.Group
import com.discord.models.domain.ModelGuildMemberListUpdate.Item
import com.discord.models.domain.ModelGuildMemberListUpdate.Operation
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.lazy.memberlist.ChannelMemberList
import com.discord.utilities.lazy.memberlist.MemberListRow
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.permissions.denies
import com.discord.utilities.rx.leadingEdgeThrottle
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.PublishSubject

/**
 * The lazily-loaded member list for each channel.
 *
 * The server does not send whole member lists; it sends windowed *operations* against a sparse list
 * the client is expected to mirror exactly. An operation that references an index outside the list
 * therefore means the client and server have diverged, and the only safe response is to invalidate
 * rather than to guess — silently ignoring it would leave the wrong member rendered under a name
 * for the rest of the session.
 *
 * Member lists are shared between channels with identical view permissions, which is what
 * [computeMemberListId] derives: two channels with the same `VIEW_CHANNEL` overwrites resolve to the
 * same list id and therefore to the same subscription.
 */
class StoreChannelMembers(
    private val observationDeck: ObservationDeck,
    /**
     * Used to coalesce the notification storm a member-list sync produces into one update per
     * second. Without it the store notifies once per operation, and a large guild re-renders the
     * member list dozens of times per frame.
     */
    private val dispatcher: Dispatcher? = null,
    private val getChannel: (Long) -> Channel? = { null },
    private val getGuildRoles: (Long) -> Map<Long, GuildRole> = { emptyMap() },
    private val getGuildMemberCount: (Long) -> Int = { 0 },
    private val getRole: (guildId: Long, roleId: Long) -> GuildRole? = { _, _ -> null },
) : StoreV2() {

    /** Thrown, logged and swallowed when an operation cannot be applied. See [MemberListUpdateLogger]. */
    class MemberListUpdateException(message: String) : Exception(message)

    /**
     * A short ring of the most recent updates, attached to the report when one fails.
     *
     * A member-list desync is only diagnosable from the operations that led to it, and by the time
     * it is noticed those are gone.
     */
    object MemberListUpdateLogger {
        private const val MAX_UPDATE_COUNT = 20
        private const val ERROR_TAG = "MemberListUpdateError"

        private val opLogs = ArrayDeque<String>()

        @Synchronized
        fun logUpdate(update: ModelGuildMemberListUpdate) {
            val groups = update.groups.joinToString { "${it.id}:${it.count}" }
            opLogs.addLast("guild=${update.guildId} list=${update.id} groups=[$groups] ops=${update.ops}")
            while (opLogs.size > MAX_UPDATE_COUNT) opLogs.removeFirst()
        }

        @Synchronized
        fun dumpLogs(guildId: Long, listId: String, throwable: Throwable) {
            Log.e(
                ERROR_TAG,
                "guild=$guildId list=$listId\n" + opLogs.joinToString("\n"),
                throwable,
            )
        }

        @Synchronized
        internal fun clear() = opLogs.clear()
    }

    private data class MutableState(
        var groups: List<Group> = emptyList(),
        var onlineCount: Int = 0,
        var memberCount: Int = 0,
        val rows: MutableList<MemberListRow?> = ArrayList(),
    )

    private val memberLists = LinkedHashMap<Long, MutableMap<String, MutableState>>()
    private var memberListsSnapshot: Map<Long, Map<String, ChannelMemberList>> = emptyMap()
    private val markChangedTrigger = PublishSubject.create<Unit>()

    init {
        dispatcher?.let { storeDispatcher ->
            markChangedTrigger
                .leadingEdgeThrottle(1L, TimeUnit.SECONDS)
                .subscribe { storeDispatcher.schedule { markChanged() } }
        }
    }

    // -- Handlers -------------------------------------------------------------------------------

    @StoreThread
    fun handleGuildMemberListUpdate(update: ModelGuildMemberListUpdate) {
        MemberListUpdateLogger.logUpdate(update)
        var list = memberLists[update.guildId]?.get(update.id)
        try {
            update.ops.forEach { operation ->
                if (operation is Operation.Sync && list == null) {
                    list = MutableState().also {
                        memberLists.getOrPut(update.guildId) { LinkedHashMap() }[update.id] = it
                    }
                }
                list?.apply(operation, update.guildId)
            }
        } catch (throwable: Throwable) {
            // The list is already wrong; report it and start over rather than render stale rows.
            MemberListUpdateLogger.dumpLogs(update.guildId, update.id, throwable)
            list?.invalidate()
        }
        list?.let {
            it.groups = update.groups.toList()
            it.onlineCount = update.groups.firstOrNull { group -> group.type == Group.Type.ONLINE }?.count ?: 0
            it.memberCount = update.groups.sumOf(Group::count)
            // Every group contributes a header row on top of its members.
            val size = update.groups.sumOf { group -> group.count + 1 }
            while (it.rows.size < size) it.rows.add(null)
            while (it.rows.size > size) it.rows.removeAt(it.rows.lastIndex)
            throttleMarkChanged()
        }
    }

    @StoreThread
    fun handleGuildRemove(guildId: Long) {
        if (memberLists.remove(guildId) != null) throttleMarkChanged()
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
        if (changed) throttleMarkChanged()
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
        if (changed) throttleMarkChanged()
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
        if (changed) throttleMarkChanged()
    }

    @StoreThread
    fun reset() {
        MemberListUpdateLogger.clear()
        if (memberLists.isEmpty()) return
        memberLists.clear()
        markChanged()
    }

    // -- Reads ----------------------------------------------------------------------------------

    fun get(guildId: Long, memberListId: String): ChannelMemberList? =
        memberListsSnapshot[guildId]?.get(memberListId)

    fun observe(guildId: Long, memberListId: String): Observable<ChannelMemberList?> =
        observationDeck.connectRx(this) { get(guildId, memberListId) }.distinctUntilChanged()

    /**
     * The member list for a channel, computing its list id from the channel's permissions.
     *
     * Never `null`: before the first sync arrives the caller still needs something to render, and an
     * empty list sized from the guild's member count is what produces the correct placeholder
     * height.
     */
    fun getChannelMemberList(guildId: Long, channelId: Long): ChannelMemberList {
        val listId = computeMemberListId(getChannel(channelId), getGuildRoles(guildId))
        return get(guildId, listId) ?: ChannelMemberList(
            listId = listId,
            groups = emptyList(),
            groupIndices = emptyMap(),
            onlineCount = 0,
            memberCount = getGuildMemberCount(guildId),
            rows = emptyList(),
        )
    }

    fun observeChannelMemberList(guildId: Long, channelId: Long): Observable<ChannelMemberList> =
        observationDeck.connectRx(this) { getChannelMemberList(guildId, channelId) }.distinctUntilChanged()

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        memberListsSnapshot = memberLists.mapValues { (guildId, lists) ->
            lists.mapValues { (listId, list) -> list.snapshot(guildId, listId) }
        }
    }

    // -- Internals -------------------------------------------------------------------------------

    @StoreThread
    private fun throttleMarkChanged() {
        if (dispatcher == null) markChanged() else markChangedTrigger.onNext(Unit)
    }

    @StoreThread
    private fun MutableState.apply(operation: Operation, guildId: Long) {
        when (operation) {
            is Operation.Sync -> {
                ensureSize(operation.range.first + operation.items.size)
                operation.items.forEachIndexed { offset, item -> rows[operation.range.first + offset] = item.row(guildId) }
            }
            is Operation.Update ->
                if (operation.index in rows.indices) rows[operation.index] = operation.item.row(guildId)
                else throw MemberListUpdateException("UPDATE index ${operation.index} outside 0..${rows.lastIndex}")
            is Operation.Insert ->
                if (operation.index in 0..rows.size) rows.add(operation.index, operation.item.row(guildId))
                else throw MemberListUpdateException("INSERT index ${operation.index} outside 0..${rows.size}")
            is Operation.Delete ->
                if (operation.index in rows.indices) rows.removeAt(operation.index)
                else throw MemberListUpdateException("DELETE index ${operation.index} outside 0..${rows.lastIndex}")
            is Operation.Invalidate -> {
                val end = minOf(operation.range.last, rows.lastIndex)
                if (operation.range.first <= end) for (index in operation.range.first..end) rows[index] = null
            }
        }
    }

    @StoreThread
    private fun MutableState.ensureSize(size: Int) {
        while (rows.size < size) rows.add(null)
    }

    @StoreThread
    private fun MutableState.invalidate() {
        rows.indices.forEach { rows[it] = null }
    }

    @StoreThread
    private fun Item.row(guildId: Long): MemberListRow = when (this) {
        is Item.MemberItem -> MemberListRow.Member(member.detached())
        is Item.GroupItem -> MemberListRow.GroupHeader(
            group,
            group.id.toLongOrNull()?.let { getRole(guildId, it)?.name },
        )
    }

    @StoreThread
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
        /**
         * The id of the member list a channel subscribes to.
         *
         * Channels that @everyone can view share the single `everyone` list. Anything else is keyed
         * by a hash of its `VIEW_CHANNEL` overwrites, so two private channels with the same
         * allow/deny set share one list and one subscription.
         */
        @JvmStatic
        fun computeMemberListId(channel: Channel?, roles: Map<Long, GuildRole>): String {
            if (channel == null) return ModelGuildMemberListUpdate.EVERYONE_ID
            channel.memberListId?.let { return it }
            // `canEveryone` treats a missing @everyone role as permissive: during partial hydration
            // the role may not have arrived yet, and assuming "private" would subscribe every
            // channel to a hashed list the server never sends.
            return if (PermissionUtils.canEveryone(Permission.VIEW_CHANNEL, channel, null, roles)) {
                ModelGuildMemberListUpdate.EVERYONE_ID
            } else {
                computeIdFromOverwrites(channel.permissionOverwrites)
            }
        }

        /**
         * `murmur3_32("allow:<id>,deny:<id>,…")` as an unsigned 32-bit decimal string.
         *
         * A channel with no overwrite list at all yields `""`; a channel with an empty list still
         * hashes, which is why the two cases differ.
         */
        private fun computeIdFromOverwrites(overwrites: List<PermissionOverwrite>?): String {
            if (overwrites == null) return ""
            val key = overwrites.mapNotNull { overwrite ->
                when {
                    overwrite.allows(Permission.VIEW_CHANNEL) -> "allow:${overwrite.id}"
                    overwrite.denies(Permission.VIEW_CHANNEL) -> "deny:${overwrite.id}"
                    else -> null
                }
            }.sorted().joinToString(",")
            return (murmur3(key).toLong() and 0xFFFFFFFFL).toString()
        }

        /** Local until `utilities/PermissionOverwriteUtils` is reconstructed. */
        private fun PermissionOverwrite.allows(permission: Long): Boolean = (permission and allow) != 0L

        /** murmur3_32 over UTF-8 with seed 0, matching the server's list-id derivation. */
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
