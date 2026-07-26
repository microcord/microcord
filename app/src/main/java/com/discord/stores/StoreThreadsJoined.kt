package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.channel.isThread
import com.discord.api.guild.Guild
import com.discord.api.thread.ThreadMember
import com.discord.api.thread.ThreadMemberUpdate
import com.discord.api.thread.ThreadMembersUpdate
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.domain.ModelMuteConfig
import com.discord.models.domain.ModelPayload
import com.discord.models.thread.dto.ModelThreadListSync
import com.discord.stores.updates.ObservationDeck
import rx.Observable

/**
 * Membership of the current user in every thread the client knows about.
 *
 * This is the authoritative answer to "have I joined this thread", which drives private-thread
 * permission computation ([StorePermissions]), the join/leave affordance in the thread member list,
 * and the per-thread notification flags used by [StoreUserGuildSettings].
 *
 * Only rows belonging to the signed-in user are stored: every gateway handler filters on
 * [StoreUser.getMe]. The store keeps no channel state — pair it with [StoreThreadsActive] through
 * [StoreThreadsActiveJoined] when both the channel and the membership are needed.
 */
class StoreThreadsJoined(
    private val storeUser: StoreUser,
    private val observationDeck: ObservationDeck,
) : StoreV2() {

    /** One row of thread membership for the signed-in user. */
    data class JoinedThread(
        val threadId: Long,
        val guildId: Long,
        val flags: Int,
        val muted: Boolean,
        val muteConfig: ModelMuteConfig,
        val joinTimestamp: UtcDateTime,
    )

    private val joinedThreads = HashMap<Long, JoinedThread>()
    private var joinedThreadsSnapshot: Map<Long, JoinedThread> = emptyMap()

    // -- Reads ----------------------------------------------------------------------------------

    fun getAllJoinedThreads(): Map<Long, JoinedThread> = joinedThreadsSnapshot

    fun getJoinedThread(threadId: Long): JoinedThread? = joinedThreadsSnapshot[threadId]

    fun hasJoined(threadId: Long): Boolean = joinedThreadsSnapshot.containsKey(threadId)

    @StoreThread
    internal fun getAllJoinedThreadsInternal(): Map<Long, JoinedThread> = joinedThreads

    @StoreThread
    fun hasJoinedInternal(threadId: Long): Boolean = joinedThreads.containsKey(threadId)

    fun observeJoinedThread(threadId: Long): Observable<JoinedThread?> =
        observationDeck.connectRx(this) { getJoinedThread(threadId) }

    fun observeJoinedThreads(): Observable<Map<Long, JoinedThread>> =
        observationDeck.connectRx(this) { getAllJoinedThreads() }

    // -- Gateway handlers -----------------------------------------------------------------------

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        joinedThreads.clear()
        payload.guilds.orEmpty().forEach(::saveThreads)
        markChanged()
    }

    @StoreThread
    fun handleGuildCreate(guild: Guild) {
        deleteThreads(guild.id)
        saveThreads(guild)
    }

    @StoreThread
    fun handleGuildDelete(guildId: Long) = deleteThreads(guildId)

    /**
     * Absorb the thread membership rows returned alongside a message-search response.
     *
     * The reference takes the whole `SearchState`, reads the guild id from the first hit thread and
     * the rows from `searchState.threadMembers`. `SearchState` does not exist in this project yet,
     * so the two fields it actually consumes are passed directly.
     *
     * Reference divergence reproduced deliberately: this handler does **not** `markChanged()`, so
     * search-discovered memberships only become observable on the next unrelated dispatch.
     */
    @StoreThread
    fun handleSearchFinish(threads: List<Channel>?, threadMembers: List<ThreadMember>?) {
        val guildId = threads?.firstOrNull()?.guildId ?: return
        val meId = storeUser.getMe().id
        threadMembers.orEmpty()
            .filter { it.userId == meId }
            .forEach { member -> joinedThreads[member.id] = member.toJoinedThread(guildId) }
    }

    @StoreThread
    fun handleThreadCreateOrUpdate(channel: Channel) {
        val member = channel.member ?: return
        if (member.userId != storeUser.getMe().id) return
        joinedThreads[channel.id] = member.toJoinedThread(channel.guildId)
        markChanged()
    }

    @StoreThread
    fun handleThreadDelete(channel: Channel) {
        if (joinedThreads.remove(channel.id) != null) markChanged()
    }

    @StoreThread
    fun handleThreadListSync(payload: ModelThreadListSync) {
        payload.members.orEmpty().forEach { member ->
            joinedThreads[member.id] = member.toJoinedThread(payload.guildId)
            markChanged()
        }
    }

    @StoreThread
    fun handleThreadMemberUpdate(payload: ThreadMemberUpdate) {
        if (payload.userId != storeUser.getMe().id) return
        joinedThreads[payload.id] = JoinedThread(
            threadId = payload.id,
            guildId = payload.guildId,
            flags = payload.flags,
            muted = payload.muted,
            muteConfig = ModelMuteConfig(payload.muteConfig),
            joinTimestamp = payload.joinTimestamp,
        )
        markChanged()
    }

    @StoreThread
    fun handleThreadMembersUpdate(payload: ThreadMembersUpdate) {
        val meId = storeUser.getMe().id
        if (joinedThreads.containsKey(payload.id) && payload.removedMemberIds?.contains(meId) == true) {
            joinedThreads.remove(payload.id)
            markChanged()
        }
        val added = payload.addedMembers?.firstOrNull { it.userId == meId } ?: return
        joinedThreads[payload.id] = JoinedThread(
            threadId = payload.id,
            guildId = payload.guildId,
            flags = added.flags,
            muted = added.muted,
            muteConfig = ModelMuteConfig(added.muteConfig),
            joinTimestamp = added.joinTimestamp,
        )
        markChanged()
    }

    /**
     * Drop every row on logout.
     *
     * The reference has no `reset()`; it relies on the next `READY` clearing the map. This project
     * wipes user state at logout instead, so the hook exists to match the surrounding store graph.
     */
    internal fun reset() {
        joinedThreads.clear()
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        joinedThreadsSnapshot = HashMap(joinedThreads)
    }

    // -- Helpers --------------------------------------------------------------------------------

    private fun saveThreads(guild: Guild) {
        guild.threads.orEmpty().forEach { thread ->
            if (!thread.isThread()) return@forEach
            val member = thread.member ?: return@forEach
            joinedThreads[thread.id] = JoinedThread(
                threadId = thread.id,
                guildId = guild.id,
                flags = member.flags,
                muted = member.muted,
                muteConfig = ModelMuteConfig(member.muteConfig),
                joinTimestamp = member.joinTimestamp,
            )
            markChanged()
        }
    }

    private fun deleteThreads(guildId: Long) {
        if (joinedThreads.values.removeAll { it.guildId == guildId }) markChanged()
    }

    private fun ThreadMember.toJoinedThread(guildId: Long) = JoinedThread(
        threadId = id,
        guildId = guildId,
        flags = flags,
        muted = muted,
        muteConfig = ModelMuteConfig(muteConfig),
        joinTimestamp = joinTimestamp,
    )

    companion object {
        /** Every notification bit plus `HAS_INTERACTED`. */
        const val ALL_FLAGS = 15

        /** The three mutually exclusive notification bits, without `HAS_INTERACTED`. */
        const val NOTIFICATION_FLAGS = 14
    }
}
