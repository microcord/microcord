package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.guild.Guild
import com.discord.api.thread.ThreadMemberUpdate
import com.discord.api.thread.ThreadMembersUpdate
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.thread.dto.ModelThreadListSync
import com.discord.stores.updates.ObservationDeck
import rx.Observable

/**
 * The intersection of [StoreThreadsActive] and [StoreThreadsJoined]: unarchived threads the signed-in
 * user is a member of, carrying the channel, the join time and the mute flag together.
 *
 * This is the projection unread badges and the channel-list thread rows read from — a thread only
 * contributes unread state when it is both active and joined, and neither source store alone can
 * answer that. The store owns no truth of its own: every handler recomputes affected rows from the
 * two upstream stores' store-thread-confined maps, so it can never disagree with them.
 */
class StoreThreadsActiveJoined(
    private val storeThreadsActive: StoreThreadsActive,
    private val storeThreadsJoined: StoreThreadsJoined,
    private val observationDeck: ObservationDeck,
) : StoreV2() {

    data class ActiveJoinedThread(
        val channel: Channel,
        val joinTimestamp: UtcDateTime,
        val muted: Boolean,
    )

    private val activeJoinedThreads = HashMap<Long, ActiveJoinedThread>()
    private var activeJoinedThreadsByThreadIdSnapshot: Map<Long, ActiveJoinedThread> = emptyMap()

    /** guild id → parent channel id → thread id → row. Rebuilt wholesale in [snapshotData]. */
    private var activeJoinedThreadsHierarchicalSnapshot:
        Map<Long, Map<Long, Map<Long, ActiveJoinedThread>>> = emptyMap()

    // -- Reads ----------------------------------------------------------------------------------

    @StoreThread
    internal fun getActiveJoinedThreadsInternal(): Map<Long, ActiveJoinedThread> = activeJoinedThreads

    @StoreThread
    internal fun getActiveJoinedThreadsForChannelInternal(channelId: Long): List<Channel> =
        activeJoinedThreads.values.filter { it.channel.parentId == channelId }.map(ActiveJoinedThread::channel)

    fun getAllActiveJoinedThreadsById(): Map<Long, ActiveJoinedThread> =
        activeJoinedThreadsByThreadIdSnapshot

    fun observeAllActiveJoinedThreadsById(): Observable<Map<Long, ActiveJoinedThread>> =
        observationDeck.connectRx(this) { activeJoinedThreadsByThreadIdSnapshot }

    fun observeAllActiveJoinedThreadsChannelsById(): Observable<Map<Long, Channel>> =
        observationDeck.connectRx(this) {
            activeJoinedThreadsByThreadIdSnapshot.mapValues { it.value.channel }
        }

    fun observeActiveJoinedThreadsForGuild(
        guildId: Long,
    ): Observable<Map<Long, Map<Long, ActiveJoinedThread>>> =
        observationDeck.connectRx(this) {
            activeJoinedThreadsHierarchicalSnapshot[guildId].orEmpty()
        }.distinctUntilChanged()

    fun observeActiveJoinedThreadsForChannel(
        guildId: Long,
        channelId: Long,
    ): Observable<Map<Long, ActiveJoinedThread>> =
        observationDeck.connectRx(this) {
            activeJoinedThreadsHierarchicalSnapshot[guildId]?.get(channelId).orEmpty()
        }.distinctUntilChanged()

    fun observeActiveJoinedThreadsChannelsForGuild(guildId: Long): Observable<Map<Long, Channel>> =
        observationDeck.connectRx(this) {
            activeJoinedThreadsHierarchicalSnapshot[guildId].orEmpty().values
                .flatMap { byThreadId -> byThreadId.entries }
                .associate { (threadId, row) -> threadId to row.channel }
        }.distinctUntilChanged()

    // -- Gateway handlers -----------------------------------------------------------------------

    @StoreThread
    fun handleConnectionOpen() {
        activeJoinedThreads.clear()
        saveThreads()
        markChanged()
    }

    /** A parent channel changed; its threads may have been rewritten by [StoreThreadsActive]. */
    @StoreThread
    fun handleChannelCreateOrUpdate(channel: Channel) {
        getActiveJoinedThreadsForChannelInternal(channel.id)
            .forEach { thread -> updateThread(thread.guildId, thread.id) }
    }

    @StoreThread
    fun handleGuildCreate(guild: Guild) = saveThreads(guild.id)

    @StoreThread
    fun handleGuildDelete(guildId: Long) {
        val iterator = activeJoinedThreads.values.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().channel.guildId == guildId) {
                iterator.remove()
                markChanged()
            }
        }
    }

    @StoreThread
    fun handleThreadCreateOrUpdateOrDelete(channel: Channel) = updateThread(channel.guildId, channel.id)

    @StoreThread
    fun handleThreadListSync(payload: ModelThreadListSync) {
        handleGuildDelete(payload.guildId)
        saveThreads(payload.guildId)
    }

    @StoreThread
    fun handleThreadMemberUpdate(payload: ThreadMemberUpdate) = updateThread(payload.guildId, payload.id)

    @StoreThread
    fun handleThreadMembersUpdate(payload: ThreadMembersUpdate) = updateThread(payload.guildId, payload.id)

    /**
     * Drop the projection on logout.
     *
     * The reference has no `reset()`; this project wipes user state at logout, so the hook exists to
     * match the surrounding store graph.
     */
    internal fun reset() {
        activeJoinedThreads.clear()
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        activeJoinedThreadsByThreadIdSnapshot = HashMap(activeJoinedThreads)
        activeJoinedThreadsHierarchicalSnapshot = activeJoinedThreads.values
            .groupBy { it.channel.guildId }
            .mapValues { (_, guildRows) ->
                guildRows.groupBy { it.channel.parentId }
                    .mapValues { (_, parentRows) -> parentRows.associateBy { it.channel.id } }
            }
    }

    // -- Helpers --------------------------------------------------------------------------------

    /** Re-derive every row, or only those of [guildId] when it is given. */
    @StoreThread
    private fun saveThreads(guildId: Long? = null) {
        val activeByGuild = storeThreadsActive.getAllActiveThreadsInternal()
        storeThreadsJoined.getAllJoinedThreadsInternal().values.forEach { joined ->
            if (guildId != null && joined.guildId != guildId) return@forEach
            val channel = activeByGuild[joined.guildId]?.get(joined.threadId) ?: return@forEach
            activeJoinedThreads[channel.id] =
                ActiveJoinedThread(channel, joined.joinTimestamp, joined.muted)
            markChanged()
        }
    }

    @StoreThread
    private fun updateThread(guildId: Long, channelId: Long) {
        val channel = storeThreadsActive.getAllActiveThreadsInternal()[guildId]?.get(channelId)
        val joined = storeThreadsJoined.getAllJoinedThreadsInternal()[channelId]
        if (channel != null && joined != null) {
            activeJoinedThreads[channelId] =
                ActiveJoinedThread(channel, joined.joinTimestamp, joined.muted)
            markChanged()
        } else if (activeJoinedThreads.remove(channelId) != null) {
            markChanged()
        }
    }
}
