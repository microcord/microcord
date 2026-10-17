package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.channel.isArchivedThread
import com.discord.api.channel.isGuildForumPost
import com.discord.api.channel.isThread
import com.discord.models.thread.dto.ModelThreadListSync
import com.discord.stores.updates.ObservationDeck
import rx.Observable

/**
 * The set of *unarchived* threads, indexed for the surfaces that need them.
 *
 * [StoreChannels] is the system of record for thread channels; this store is a projection of it
 * that (a) excludes archived threads, (b) keeps a guild → thread-id → channel hierarchy so a channel
 * or guild can be queried without scanning, (c) maintains a separate index of forum posts (threads
 * whose parent is a `GUILD_FORUM` channel), and (d) records which guilds have received a
 * `THREAD_LIST_SYNC` so the thread browser can distinguish "no threads" from "not synced yet".
 *
 * Every write path ends in [rebuildThreadByIdMaps] or an equivalent targeted update, so the flat
 * indexes never drift from [threadsByGuild].
 */
class StoreThreadsActive(
    private val storeChannels: StoreChannels,
    private val observationDeck: ObservationDeck,
) : StoreV2() {

    private val threadsById = HashMap<Long, Channel>()
    private var threadsByIdSnapshot: Map<Long, Channel> = emptyMap()
    private val forumThreadsById = HashMap<Long, Channel>()
    private var forumThreadsByIdSnapshot: Map<Long, Channel> = emptyMap()
    private val threadsByGuild = HashMap<Long, MutableMap<Long, Channel>>()
    private var threadsByGuildSnapshot: Map<Long, Map<Long, Channel>> = emptyMap()
    private val threadSyncedGuilds = LinkedHashSet<Long>()
    private var threadSyncedGuildsSnapshot: Set<Long> = emptySet()

    // -- Reads ----------------------------------------------------------------------------------

    @StoreThread
    internal fun getAllActiveThreadsByIdInternal(): Map<Long, Channel> = threadsById

    @StoreThread
    internal fun getAllActiveThreadsInternal(): Map<Long, Map<Long, Channel>> = threadsByGuild

    @StoreThread
    internal fun getAllForumThreadsByIdInternal(): Map<Long, Channel> = forumThreadsById

    fun getAllActiveThreadsById(): Map<Long, Channel> = threadsByIdSnapshot

    fun getAllForumThreadsById(): Map<Long, Channel> = forumThreadsByIdSnapshot

    fun observeActiveThreadsForChannel(guildId: Long, channelId: Long?): Observable<Map<Long, Channel>> =
        observationDeck.connectRx(this) { getActiveThreadsForChannel(guildId, channelId) }
            .distinctUntilChanged()

    fun observeActiveThreadsForGuild(guildId: Long): Observable<Map<Long, Channel>> =
        observationDeck.connectRx(this) { getActiveThreadsForGuild(guildId) }.distinctUntilChanged()

    fun observeAllActiveThreadsById(): Observable<Map<Long, Channel>> =
        observationDeck.connectRx(this) { threadsByIdSnapshot }

    fun observeAllActiveForumThreadsById(): Observable<Map<Long, Channel>> =
        observationDeck.connectRx(this) { forumThreadsByIdSnapshot }

    fun observeChannelsWithActiveThreadsByGuild(guildId: Long): Observable<Set<Long>> =
        observationDeck.connectRx(this) { getChannelsWithActiveThreadsByGuild(guildId) }
            .distinctUntilChanged()

    fun observeThreadSyncedGuilds(): Observable<Set<Long>> =
        observationDeck.connectRx(this) { threadSyncedGuildsSnapshot }

    fun observeIsThreadSyncedGuild(guildId: Long): Observable<Boolean> =
        observationDeck.connectRx(this) { threadSyncedGuildsSnapshot.contains(guildId) }

    // -- Gateway handlers -----------------------------------------------------------------------

    @StoreThread
    fun handleConnectionOpen() {
        threadsById.clear()
        forumThreadsById.clear()
        threadsByGuild.clear()
        threadSyncedGuilds.clear()
        syncThreadsFromChannelStore()
        markChanged()
    }

    /**
     * A parent channel changed. Only the NSFW flag propagates to child threads, so re-read the
     * affected threads from [StoreChannels] — which has already applied the inherited flag — rather
     * than patching them here.
     */
    @StoreThread
    fun handleChannelCreateOrUpdate(channel: Channel) {
        val guildThreads = threadsByGuild[channel.guildId] ?: return
        val stale = guildThreads.values.filter { it.parentId == channel.id && it.nsfw != channel.nsfw }
        if (stale.isEmpty()) return
        stale.forEach { thread ->
            storeChannels.getChannelInternal(thread.guildId, thread.id)?.let { guildThreads[it.id] = it }
        }
        rebuildThreadByIdMaps()
        markChanged()
    }

    @StoreThread
    fun handleGuildCreate() = syncThreadsFromChannelStore()

    @StoreThread
    fun handleGuildDelete(guildId: Long) = deleteThreads(guildId)

    @StoreThread
    fun handleThreadCreateOrUpdate(channel: Channel) {
        if (!channel.isThread()) return
        if (channel.isArchivedThread()) {
            deleteThread(channel)
            return
        }
        threadsByGuild.getOrPut(channel.guildId) { HashMap() }[channel.id] = channel
        threadsById[channel.id] = channel
        if (channel.isGuildForumPost(storeChannels.getChannelInternal(channel.guildId, channel.parentId))) {
            forumThreadsById[channel.id] = channel
        }
        markChanged()
    }

    @StoreThread
    fun handleThreadDelete(channel: Channel) = deleteThread(channel)

    /**
     * Reference divergence reproduced deliberately: the forum-post index is updated for **every**
     * thread in the payload, including archived ones and non-thread channels, because the reference
     * places that branch outside the `isThread` guard.
     */
    @StoreThread
    fun handleThreadListSync(payload: ModelThreadListSync) {
        val guildThreads = threadsByGuild.getOrPut(payload.guildId) { HashMap() }
        // The sync replaces the listed channels' threads (the whole guild without `channel_ids`), as
        // RN's ActiveThreadsStore does. Otherwise a post the server auto-archived while nobody was
        // watching stays listed, since no THREAD_UPDATE ever reports it.
        val channelIds = payload.channelIds?.toSet()
        if (channelIds == null) guildThreads.clear() else guildThreads.values.removeAll { it.parentId in channelIds }
        payload.threads.forEach { thread -> if (thread.isThread()) guildThreads[thread.id] = thread }
        rebuildThreadByIdMaps()
        threadSyncedGuilds.add(payload.guildId)
        markChanged()
    }

    /**
     * Drop every index on logout.
     *
     * The reference has no `reset()` and relies on `READY` to rebuild; this project wipes user state
     * at logout, so the hook exists to match the surrounding store graph.
     */
    internal fun reset() {
        threadsById.clear()
        forumThreadsById.clear()
        threadsByGuild.clear()
        threadSyncedGuilds.clear()
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        threadsByIdSnapshot = HashMap(threadsById)
        forumThreadsByIdSnapshot = HashMap(forumThreadsById)
        threadsByGuildSnapshot = threadsByGuild.mapValues { HashMap(it.value) }
        threadSyncedGuildsSnapshot = threadSyncedGuilds.toSet()
    }

    // -- Helpers --------------------------------------------------------------------------------

    private fun getActiveThreadsForChannel(guildId: Long, channelId: Long?): Map<Long, Channel> {
        if (channelId == null) return emptyMap()
        return threadsByGuildSnapshot[guildId].orEmpty().filterValues { it.parentId == channelId }
    }

    private fun getActiveThreadsForGuild(guildId: Long): Map<Long, Channel> =
        threadsByGuildSnapshot[guildId].orEmpty()

    private fun getChannelsWithActiveThreadsByGuild(guildId: Long): Set<Long> =
        threadsByGuildSnapshot[guildId].orEmpty().values.mapTo(HashSet<Long>(), Channel::parentId)

    private fun deleteThread(channel: Channel) {
        if (threadsByGuild[channel.guildId]?.remove(channel.id) != null) markChanged()
        if (threadsById.remove(channel.id) != null) markChanged()
        if (forumThreadsById.remove(channel.id) != null) markChanged()
    }

    private fun deleteThreads(guildId: Long) {
        if (threadsByGuild.remove(guildId) == null) return
        rebuildThreadByIdMaps()
        markChanged()
    }

    @StoreThread
    private fun rebuildThreadByIdMaps() {
        threadsById.clear()
        forumThreadsById.clear()
        threadsByGuild.forEach { (guildId, guildThreads) ->
            guildThreads.forEach { (threadId, thread) ->
                threadsById[threadId] = thread
                if (thread.isGuildForumPost(storeChannels.getChannelInternal(guildId, thread.parentId))) {
                    forumThreadsById[threadId] = thread
                }
            }
        }
    }

    @StoreThread
    private fun syncThreadsFromChannelStore() {
        threadsByGuild.clear()
        storeChannels.getThreadsByIdInternal().forEach { (threadId, thread) ->
            if (!thread.isThread()) return@forEach
            if (thread.isArchivedThread()) return@forEach
            threadsByGuild.getOrPut(thread.guildId) { HashMap() }[threadId] = thread
        }
        rebuildThreadByIdMaps()
        markChanged()
    }
}
