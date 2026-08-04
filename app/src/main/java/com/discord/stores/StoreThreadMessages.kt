package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.channel.isThread
import com.discord.api.guild.Guild
import com.discord.models.domain.ModelMessageDelete
import com.discord.models.domain.ModelPayload
import com.discord.models.message.Message
import com.discord.models.thread.dto.ModelThreadListSync
import com.discord.stores.updates.ObservationDeck
import rx.Observable
import com.discord.api.message.Message as ApiMessage

/**
 * Per-thread message count and most-recent message, for the thread preview rows.
 *
 * Neither value is authoritative: the count is capped at [MAX_THREAD_MESSAGE_COUNT] (the preview
 * renders "50+" beyond that) and the most-recent message is whatever the client currently knows,
 * cleared to `null` whenever the server reports a `lastMessageId` the client has not loaded. The
 * preview then falls back to showing no excerpt rather than a stale one.
 *
 * The count and the message are maintained independently: the count follows `message_count` from
 * channel payloads and is incremented locally on `MESSAGE_CREATE`, while the message follows the
 * loaded history in [StoreMessages].
 */
class StoreThreadMessages(
    private val storeMessages: StoreMessages,
    private val storeChannels: StoreChannels,
    private val observationDeck: ObservationDeck,
) : StoreV2() {

    data class ThreadState(
        val threadId: Long,
        val guildId: Long,
        val parentId: Long,
        var count: Int,
        var mostRecentMessage: Message?,
    )

    private val threads = HashMap<Long, ThreadState>()
    private var threadsSnapshot: Map<Long, ThreadState> = emptyMap()

    // -- Reads ----------------------------------------------------------------------------------

    fun getThreadMessageCount(channelId: Long): Int? = threadsSnapshot[channelId]?.count

    fun observeThreadCountAndLatestMessage(): Observable<Map<Long, ThreadState>> =
        observationDeck.connectRx(this) { threadsSnapshot }.distinctUntilChanged()

    fun observeThreadCountAndLatestMessage(channelId: Long): Observable<ThreadState?> =
        observationDeck.connectRx(this) { threadsSnapshot[channelId] }.distinctUntilChanged()

    // -- Gateway handlers -----------------------------------------------------------------------

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        payload.guilds.orEmpty().forEach(::updateFromGuild)
    }

    @StoreThread
    fun handleGuildCreate(guild: Guild) = updateFromGuild(guild)

    @StoreThread
    fun handleGuildDelete(guildId: Long) = deleteForGuild(guildId)

    /** A parent channel was deleted; every thread under it goes with it. */
    @StoreThread
    fun handleChannelDelete(channel: Channel) = deleteForParentChannel(channel.id)

    @StoreThread
    fun handleThreadCreateOrUpdate(thread: Channel) = updateFromThread(thread)

    @StoreThread
    fun handleThreadDelete(channel: Channel) {
        if (threads.remove(channel.id) != null) markChanged()
    }

    @StoreThread
    fun handleThreadListSync(threadListSync: ModelThreadListSync) {
        threadListSync.threads.forEach(::updateFromThread)
        threadListSync.mostRecentMessages?.forEach { message ->
            val state = threads[message.channelId] ?: return@forEach
            threads[message.channelId] = state.copy(mostRecentMessage = message)
        }
    }

    @StoreThread
    fun handleMessageCreate(message: ApiMessage) {
        val channel = storeChannels.getChannel(message.channelId) ?: return
        if (!channel.isThread()) return
        val state = getOrAddState(channel)
        threads[channel.id] = state.copy(
            count = minOf(state.count + 1, MAX_THREAD_MESSAGE_COUNT),
            mostRecentMessage = Message(message),
        )
        markChanged()
    }

    @StoreThread
    fun handleMessageUpdate(message: ApiMessage) {
        val state = threads[message.channelId] ?: return
        val mostRecent = state.mostRecentMessage ?: return
        if (mostRecent.id != message.id) return
        threads[message.channelId] = state.copy(
            mostRecentMessage = storeMessages.getMessage(message.channelId, message.id),
        )
        markChanged()
    }

    /** Only the excerpt is invalidated; the count is left alone because the server owns it. */
    @StoreThread
    fun handleMessageDelete(messageDeleteBulk: ModelMessageDelete) {
        val state = threads[messageDeleteBulk.channelId] ?: return
        val mostRecentId = state.mostRecentMessage?.id ?: return
        if (messageDeleteBulk.messageIds.orEmpty().none { it == mostRecentId }) return
        threads[messageDeleteBulk.channelId] = state.copy(mostRecentMessage = null)
        markChanged()
    }

    /**
     * Reconcile with a freshly loaded page of history.
     *
     * Two independent jobs: seed state for any thread *started from* a message in the page, and — if
     * the page is the thread's own history — recompute its count and excerpt from what was loaded.
     * A `THREAD_STARTER_MESSAGE` (type 21) is the parent message mirrored into the thread, so it is
     * excluded from both the count and the excerpt.
     */
    @StoreThread
    fun handleMessagesLoaded(chunk: StoreMessagesLoader.ChannelChunk) {
        var discovered = false
        chunk.messages.forEach { message ->
            if (!message.hasThread() || threads.containsKey(message.id)) return@forEach
            storeChannels.getThreadsByIdInternal()[message.id]?.let { thread ->
                updateFromThread(thread)
                discovered = true
            }
        }
        if (discovered) markChanged()

        if (!chunk.isPresent) return
        val thread = storeChannels.getThreadsByIdInternal()[chunk.channelId] ?: return
        if (!thread.isThread()) return
        val state = getOrAddState(thread)
        threads[thread.id] = if (chunk.messages.isEmpty()) {
            state.copy(count = 0, mostRecentMessage = null)
        } else {
            val newest = storeMessages.getMessage(chunk.channelId, chunk.messages.first().id)
            val hasStarter = chunk.messages.any { it.type == THREAD_STARTER_MESSAGE_TYPE }
            val count = if (hasStarter) chunk.messages.size - 1 else chunk.messages.size
            state.copy(
                count = minOf(count, MAX_THREAD_MESSAGE_COUNT),
                mostRecentMessage = newest.takeUnless { it?.type == THREAD_STARTER_MESSAGE_TYPE },
            )
        }
        markChanged()
    }

    /**
     * Drop every thread's counters on logout.
     *
     * The reference has no `reset()`; this project wipes user state at logout, so the hook exists to
     * match the surrounding store graph.
     */
    internal fun reset() {
        threads.clear()
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        threadsSnapshot = HashMap(threads)
    }

    // -- Helpers --------------------------------------------------------------------------------

    @StoreThread
    private fun getOrAddState(thread: Channel): ThreadState =
        threads[thread.id] ?: ThreadState(
            threadId = thread.id,
            guildId = thread.guildId,
            parentId = thread.parentId,
            count = thread.messageCount ?: 0,
            mostRecentMessage = null,
        ).also {
            threads[thread.id] = it
            markChanged()
        }

    @StoreThread
    private fun updateFromGuild(guild: Guild) = guild.threads.orEmpty().forEach(::updateFromThread)

    @StoreThread
    private fun updateFromThread(thread: Channel) {
        var state = getOrAddState(thread)
        thread.messageCount?.let { messageCount ->
            state = state.copy(count = messageCount)
        }
        if (thread.lastMessageId != 0L && state.mostRecentMessage?.id != thread.lastMessageId) {
            state = state.copy(mostRecentMessage = null)
        }
        threads[thread.id] = state
        markChanged()
    }

    @StoreThread
    private fun deleteForGuild(guildId: Long) {
        if (threads.values.removeAll { it.guildId == guildId }) markChanged()
    }

    @StoreThread
    private fun deleteForParentChannel(parentChannelId: Long) {
        if (threads.values.removeAll { it.parentId == parentChannelId }) markChanged()
    }

    companion object {
        /** Counts above this render as "50+"; the client never tracks a larger figure. */
        const val MAX_THREAD_MESSAGE_COUNT = 50

        private const val THREAD_STARTER_MESSAGE_TYPE = 21
    }
}
