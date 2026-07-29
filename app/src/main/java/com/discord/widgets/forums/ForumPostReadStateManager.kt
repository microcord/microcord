package com.discord.widgets.forums

import com.discord.api.channel.Channel
import com.discord.stores.StoreForumPostReadStates
import com.discord.stores.StoreReadStates
import com.discord.stores.StoreStream
import com.discord.stores.StoreThreadsActive
import rx.Observable

object ForumPostReadStateManager {
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
    ) { threads, persisted, unread -> create(threads, persisted, unread) }
        .distinctUntilChanged()

    internal fun create(
        threads: Map<Long, Channel>,
        persistedChannelIds: Set<Long>,
        unreadChannelIds: Set<Long>,
    ): Map<Long, ForumPostReadState> = threads.values.associate { thread ->
        thread.id to ForumPostReadState(
            channelId = thread.id,
            hasSeenBefore = thread.id in persistedChannelIds,
            hasUnread = thread.id in unreadChannelIds,
        )
    }
}
