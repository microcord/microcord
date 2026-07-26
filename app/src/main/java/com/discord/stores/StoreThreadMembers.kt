package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.channel.isArchivedThread
import com.discord.api.thread.ThreadListMember
import com.discord.api.thread.ThreadMemberListUpdate
import com.discord.api.thread.ThreadMembersUpdate
import com.discord.stores.updates.ObservationDeck
import rx.Observable

/**
 * Per-thread member rosters, as user ids.
 *
 * A thread member list is only ever populated by an explicit `THREAD_MEMBER_LIST_UPDATE` — the
 * client subscribes to a thread's member list, the server replies with the full roster, and
 * subsequent `THREAD_MEMBERS_UPDATE` events patch it incrementally. A thread with no entry here has
 * never been subscribed to, which is deliberately distinct from a thread with an empty roster.
 *
 * Only ids are stored; the member row and presence that arrive alongside them belong to
 * [StoreGuilds] and [StoreUserPresence], and the member-list UI resolves them from there.
 */
class StoreThreadMembers(
    private val observationDeck: ObservationDeck,
    private val storeChannels: StoreChannels,
) : StoreV2() {

    private val memberLists = LinkedHashMap<Long, MutableSet<Long>>()
    private var memberListsSnapshot: Map<Long, Set<Long>> = emptyMap()

    // -- Reads ----------------------------------------------------------------------------------

    fun getThreadMembers(channelId: Long): Set<Long> = memberListsSnapshot[channelId].orEmpty()

    fun observeThreadMembers(channelId: Long): Observable<Set<Long>> =
        observationDeck.connectRx(this) { memberListsSnapshot[channelId].orEmpty() }
            .distinctUntilChanged()

    // -- Gateway handlers -----------------------------------------------------------------------

    @StoreThread
    fun handleConnectionOpen() {
        memberLists.clear()
        markChanged()
    }

    /** Archiving a thread tears down its member list; the server stops sending updates for it. */
    @StoreThread
    fun handleThreadCreateOrUpdate(channel: Channel) {
        if (!memberLists.containsKey(channel.id)) return
        if (!channel.isArchivedThread()) return
        memberLists.remove(channel.id)
        markChanged()
    }

    @StoreThread
    fun handleThreadDelete(channel: Channel) {
        if (memberLists.remove(channel.id) != null) markChanged()
    }

    /**
     * Replace one thread's roster wholesale.
     *
     * The list is keyed by the resolved channel's id rather than the payload's `thread_id`, and the
     * update is dropped entirely when the thread is unknown to [StoreChannels] — an update for a
     * thread the client cannot place would produce a roster nothing can ever read.
     *
     * The reference body was unrecoverable from JADX; this is reconstructed from the Vineflower
     * decompile of the DEX, which emits it in full.
     */
    @StoreThread
    fun handleThreadMemberListUpdate(threadMemberListUpdate: ThreadMemberListUpdate) {
        val thread = storeChannels.getChannelInternal(
            threadMemberListUpdate.guildId,
            threadMemberListUpdate.threadId,
        ) ?: return
        memberLists[thread.id] = threadMemberListUpdate.members
            .orEmpty()
            .mapTo(LinkedHashSet<Long>(), ThreadListMember::userId)
        markChanged()
    }

    /**
     * Patch a roster that is already loaded. Threads with no loaded roster are skipped: applying a
     * delta to nothing would fabricate a partial list the UI would render as complete.
     */
    @StoreThread
    fun handleThreadMembersUpdate(threadMembersUpdate: ThreadMembersUpdate) {
        val members = memberLists[threadMembersUpdate.id] ?: return
        threadMembersUpdate.addedMembers?.forEach { added ->
            val userId = added.member?.let { it.user.id } ?: return@forEach
            members.add(userId)
            markChanged()
        }
        threadMembersUpdate.removedMemberIds?.forEach { userId ->
            members.remove(userId)
            markChanged()
        }
    }

    /**
     * Drop every roster on logout.
     *
     * The reference has no `reset()` and relies on `READY`; this project wipes user state at logout,
     * so the hook exists to match the surrounding store graph.
     */
    internal fun reset() {
        memberLists.clear()
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        memberListsSnapshot = memberLists.mapValues { it.value.toSet() }
    }
}
