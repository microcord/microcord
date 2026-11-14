package com.discord.widgets.forums

import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildForumChannel
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreForumPostReadStates
import com.discord.stores.StoreMessageAck
import com.discord.stores.StoreReadStates
import com.discord.stores.StoreStream
import com.discord.stores.StoreThreadsActive
import com.discord.utilities.rx.appSubscribe
import rx.Observable
import rx.Subscription
import rx.subjects.BehaviorSubject

object ForumPostReadStateManager {
    /**
     * The forum's ack as it stood just before the forum was opened. Opening it acks the forum to
     * "now", so only this earlier marker can tell which posts appeared since the last visit.
     * Seeded with null (nothing new) where the reference starts empty, so the post list can never
     * wait on it.
     */
    private val forumChannelAckBeforeChannelSelect = BehaviorSubject.create(null as StoreMessageAck.Ack?)
    private var forumChannelAckSubscription: Subscription? = null
    private var internalMessageAcks: Map<Long, StoreMessageAck.Ack> = emptyMap()
    private var lastSelectedChannelId = 0L
    private var lastSyncedGuildId = 0L

    fun initialize(
        storeMessageAck: StoreMessageAck = StoreStream.getMessageAck(),
        storeChannelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
        storeThreadsActive: StoreThreadsActive = StoreStream.getThreadsActive(),
    ) {
        forumChannelAckSubscription?.unsubscribe()
        Observable.combineLatest(
            storeMessageAck.observeAll(),
            storeChannelsSelected.observeResolvedSelectedChannel(),
            storeThreadsActive.observeThreadSyncedGuilds(),
        ) { acks, selected, synced -> Triple(acks, selected, synced) }
            .appSubscribe(
                javaClass,
                subscriptionHandler = { forumChannelAckSubscription = it },
            ) { (acks, selected, synced) ->
                val channel = selected.maybeChannel ?: return@appSubscribe
                val guildNewlySynced = channel.guildId in synced && channel.guildId != lastSyncedGuildId
                val forumNewlySelected = channel.isGuildForumChannel() && channel.id != lastSelectedChannelId
                if (guildNewlySynced) {
                    internalMessageAcks = acks
                    lastSyncedGuildId = channel.guildId
                }
                // Read from the acks of the previous emission, before selecting the forum acked it.
                if (forumNewlySelected || guildNewlySynced) {
                    forumChannelAckBeforeChannelSelect.onNext(internalMessageAcks[channel.id])
                }
                lastSelectedChannelId = selected.id
                internalMessageAcks = acks
            }
    }

    fun observeForumPostReadStates(
        guildId: Long,
        channelId: Long,
        readStates: StoreReadStates = StoreStream.getReadStates(),
        threadsActive: StoreThreadsActive = StoreStream.getThreadsActive(),
        forumReadStates: StoreForumPostReadStates = StoreStream.getForumPostReadStates(),
    ): Observable<Map<Long, ForumPostReadState>> = Observable.combineLatest(
        threadsActive.observeActiveThreadsForChannel(guildId, channelId),
        forumReadStates.observeThreadIdsWithPersistedReadStates(),
        readStates.getUnreadChannelIds(),
        forumChannelAckBeforeChannelSelect,
    ) { threads, persisted, unread, forumAck -> create(threads, persisted, unread, forumAck?.messageId) }
        .distinctUntilChanged()

    /**
     * A post is new only if it was never opened and was created after the forum's ack from before
     * this visit; without that ack nothing counts as new.
     */
    internal fun create(
        threads: Map<Long, Channel>,
        persistedChannelIds: Set<Long>,
        unreadChannelIds: Set<Long>,
        forumAckMessageId: Long?,
    ): Map<Long, ForumPostReadState> = threads.values.associate { thread ->
        val isNew = thread.id !in persistedChannelIds && forumAckMessageId != null && thread.id > forumAckMessageId
        thread.id to ForumPostReadState(
            channelId = thread.id,
            hasSeenBefore = !isNew,
            hasUnread = thread.id in unreadChannelIds,
        )
    }
}
