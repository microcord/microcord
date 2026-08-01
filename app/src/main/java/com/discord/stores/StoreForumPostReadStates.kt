package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.channel.isArchivedThread
import com.discord.api.channel.isGuildForumPost
import com.discord.api.forum.ForumUnreads
import com.discord.api.message.Message
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadState
import com.discord.stores.updates.ObservationDeck
import rx.Observable

/**
 * Unread counts for forum posts.
 *
 * Forum posts cannot use the ordinary read-state pipeline: a forum channel has hundreds of posts and
 * `READY` carries a read state only for those the user has actually opened. The client therefore
 * asks the gateway for the counts of the posts it is about to display ([requestForumUnreads]) and
 * the server answers with `FORUM_UNREADS`.
 *
 * Two pieces of state come out of that:
 * - [threadUnreadCounts] — server-supplied counts, then maintained locally on message create/ack.
 * - [threadIdsWithPersistedAcks] — the set of channels `READY` carried a read state for, which is
 *   how the browser distinguishes "read" from "never opened".
 *
 * A request is issued at most once per forum channel per connection ([processedForumUnreadRequests]).
 */
class StoreForumPostReadStates(
    private val dispatcher: Dispatcher,
    /**
     * `StoreGatewayConnection.requestForumUnreads`, passed as a reference rather than the store so
     * the request payload can be asserted without a live socket. Returns whether the gateway session
     * was established, which is why the type is not `-> Unit`.
     */
    private val requestForumUnreadsOverGateway: (Long, Long, List<Pair<Long, Long>>) -> Boolean,
    private val storeThreadsActive: StoreThreadsActive,
    private val storeMessageAck: StoreMessageAck,
    private val storeChannels: StoreChannels,
    private val storeUser: StoreUser,
    private val observationDeck: ObservationDeck,
    /**
     * Reference `ForumUtils.canAccessRedesignedForumChannels(guildId)`, a guild experiment lookup.
     * `StoreExperiments` does not exist in this project yet, so the gate is injected rather than
     * defaulted — a default here would silently decide the feature for every caller.
     */
    private val canAccessForumChannels: (Long) -> Boolean,
) : StoreV2() {

    private val threadUnreadCounts = LinkedHashMap<Long, Int>()
    private var threadUnreadCountsSnapshot: Map<Long, Int> = emptyMap()
    private val threadIdsWithPersistedAcks = LinkedHashSet<Long>()
    private var threadIdsWithPersistedAcksSnapshot: Set<Long> = emptySet()
    private val processedForumUnreadRequests = LinkedHashSet<Long>()

    // -- Reads ----------------------------------------------------------------------------------

    fun getThreadUnreadCounts(): Map<Long, Int> = threadUnreadCountsSnapshot

    fun getThreadIdsWithPersistedReadStates(): Set<Long> = threadIdsWithPersistedAcksSnapshot

    fun observeThreadUnreadCounts(): Observable<Map<Long, Int>> =
        observationDeck.connectRx(this) { threadUnreadCountsSnapshot }

    fun observeThreadIdsWithPersistedReadStates(): Observable<Set<Long>> =
        observationDeck.connectRx(this) { threadIdsWithPersistedAcksSnapshot }

    // -- Commands -------------------------------------------------------------------------------

    /**
     * Ask the gateway for the unread counts of every active thread under [channelId], paired with the
     * message the user has acked in each. Threads with no ack are omitted — the server treats those
     * as fully unread.
     */
    fun requestForumUnreads(guildId: Long, channelId: Long) = dispatcher.schedule {
        if (!canAccessForumChannels(guildId)) return@schedule
        if (!processedForumUnreadRequests.add(channelId)) return@schedule
        val acks = storeMessageAck.getAll()
        val threadMessagePairs = storeThreadsActive.getAllActiveThreadsInternal()[guildId].orEmpty()
            .filterValues { it.parentId == channelId }
            .keys
            .mapNotNull { threadId -> acks[threadId]?.let { threadId to it.messageId } }
        requestForumUnreadsOverGateway(guildId, channelId, threadMessagePairs)
    }

    // -- Gateway handlers -----------------------------------------------------------------------

    /**
     * A partial `READY` does not restate the full read-state list, so the persisted-ack set is kept
     * and only added to; a full one replaces it.
     */
    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        val readState = payload.readState
        if (readState?.isPartial != true) threadIdsWithPersistedAcks.clear()
        processedForumUnreadRequests.clear()
        threadUnreadCounts.clear()
        readState?.entries.orEmpty().mapTo(threadIdsWithPersistedAcks, ModelReadState::channelId)
        markChanged()
    }

    @StoreThread
    fun handleForumUnreads(forumUnreads: ForumUnreads) {
        if (forumUnreads.permissionDenied) return
        forumUnreads.threads.forEach { thread ->
            threadUnreadCounts[thread.threadId] = thread.count ?: 0
        }
        markChanged()
    }

    /** Acking a post clears its count locally rather than waiting for another `FORUM_UNREADS`. */
    @StoreThread
    fun handleMessageAck(readState: ModelReadState) {
        if (!threadUnreadCounts.containsKey(readState.channelId)) return
        threadUnreadCounts[readState.channelId] = 0
        markChanged()
    }

    /** The user's own message marks the post read; anyone else's increments the count. */
    @StoreThread
    fun handleMessageCreate(message: Message) {
        val channelId = message.channelId
        val thread = storeChannels.getChannel(channelId) ?: return
        val parent = storeChannels.getChannel(thread.parentId) ?: return
        if (!thread.isGuildForumPost(parent)) return
        if (message.author?.id == storeUser.getMe().id) {
            threadUnreadCounts[channelId] = 0
        } else {
            threadUnreadCounts[channelId] = (threadUnreadCounts[channelId] ?: 0) + 1
        }
        markChanged()
    }

    /** An archived post leaves the browser, so its count is dropped rather than kept stale. */
    @StoreThread
    fun handleThreadCreateOrUpdate(channel: Channel) {
        if (!channel.isArchivedThread()) return
        if (threadUnreadCounts.remove(channel.id) != null) markChanged()
    }

    @StoreThread
    fun handleThreadDelete(channel: Channel) {
        if (threadUnreadCounts.remove(channel.id) != null) markChanged()
    }

    /**
     * Drop all forum unread state on logout.
     *
     * The reference has no `reset()` and relies on `READY`; this project wipes user state at logout,
     * so the hook exists to match the surrounding store graph.
     */
    internal fun reset() {
        threadUnreadCounts.clear()
        threadIdsWithPersistedAcks.clear()
        processedForumUnreadRequests.clear()
        markChanged()
    }

    override fun snapshotData() {
        super.snapshotData()
        threadIdsWithPersistedAcksSnapshot = HashSet(threadIdsWithPersistedAcks)
        threadUnreadCountsSnapshot = HashMap(threadUnreadCounts)
    }
}
