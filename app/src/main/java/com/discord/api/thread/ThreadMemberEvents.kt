package com.discord.api.thread

import com.discord.api.guildmember.GuildMember
import com.discord.api.presence.Presence
import com.discord.api.utcdatetime.UtcDateTime

/**
 * A thread member enriched with its guild member row and presence, as sent inside the
 * `added_members` array of `THREAD_MEMBERS_UPDATE`.
 *
 * Deliberately not a data class: the reference type declares no `equals`/`hashCode`/`toString`,
 * so it compares by identity. It is also Gson-instantiated and declares no constructor, which
 * means its parameter order is not observable in the binary; the order below mirrors
 * [ThreadMember], whose first six fields it repeats.
 */
class AugmentedThreadMember(
    val id: Long,
    val userId: Long,
    val flags: Int,
    val joinTimestamp: UtcDateTime,
    val muted: Boolean,
    val muteConfig: MuteConfig?,
    val member: GuildMember?,
    val presence: Presence?,
)

/** A row of `THREAD_MEMBER_LIST_UPDATE`. */
data class ThreadListMember(
    val userId: Long,
    val member: GuildMember?,
    val presence: Presence?,
)

/** Payload of `THREAD_MEMBER_UPDATE`: the current user's membership of one thread. */
data class ThreadMemberUpdate(
    val id: Long,
    val guildId: Long,
    val userId: Long,
    val flags: Int,
    val joinTimestamp: UtcDateTime,
    val muted: Boolean,
    val muteConfig: MuteConfig?,
)

/** Payload of `THREAD_MEMBERS_UPDATE`: incremental membership changes for one thread. */
data class ThreadMembersUpdate(
    val id: Long,
    val guildId: Long,
    val memberCount: Int,
    val removedMemberIds: List<Long>?,
    val memberIdsPreview: List<Long>?,
    val addedMembers: List<AugmentedThreadMember>?,
)

/** Payload of `THREAD_MEMBER_LIST_UPDATE`: the full member list for one thread. */
data class ThreadMemberListUpdate(
    val guildId: Long,
    val threadId: Long,
    val members: List<ThreadListMember>?,
)
