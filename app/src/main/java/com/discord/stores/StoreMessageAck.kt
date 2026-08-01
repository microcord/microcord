package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.channel.isArchivedThread
import com.discord.api.channel.isManaged
import com.discord.api.channel.isTextOrVoiceChannel
import com.discord.api.channel.isThread
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadState
import com.discord.models.message.Message
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.message.MessageUtils
import com.discord.utilities.persister.Persister
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.time.Clock
import com.discord.utilities.time.TimeUtils
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription

/**
 * The per-channel read marker: which message the server believes the user has read.
 *
 * This is a *ledger*, not a fire-and-forget call. Three properties follow from that and drive the
 * whole design:
 *
 * * **Local first.** [updateAcks] moves the marker as soon as the decision is made, and the REST
 *   call follows. The badge therefore clears immediately and stays cleared if the request fails.
 * * **Batched.** Scrolling a channel produces a continuous stream of "the newest visible message
 *   is X"; [postPendingAck] debounces those into one request per settled position and drops
 *   [PendingAck.EMPTY], the sentinel meaning "nothing to ack".
 * * **Locked acks win.** A manual "mark unread" writes an [Ack] with [Ack.isLockedAck] set; the
 *   automatic pipeline refuses to overwrite it, so the channel stays unread until the user opens
 *   it again. That is the guard that makes mark-unread survive the very next scroll event.
 *
 * Threads add a fourth rule: a thread the user has not joined cannot be acked at all, and acking
 * it anyway would create a read state the server discards. [ThreadAckState] gates every write.
 */
class StoreMessageAck @JvmOverloads constructor(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val storeChannels: StoreChannels,
    private val storeMessages: StoreMessages,
    private val storeMessagesMostRecent: StoreMessagesMostRecent,
    private val storeThreadsActiveJoined: StoreThreadsActiveJoined,
    private val storeThreadsActive: StoreThreadsActive,
    private val storeGuilds: StoreGuilds,
    private val clock: Clock,
    private val meId: () -> Long,
    /** Posts the ack. `StoreStream` owns the REST client, so the call is injected. */
    private val ackRequest: (channelId: Long, messageId: Long, isLockedAck: Boolean, mentionCount: Int) -> Unit,
    /**
     * Folds newly-unread messages into the mention counter and returns the resulting count.
     *
     * Wired to `StoreMentions.processMarkUnread`; defaulted so this store is usable standalone.
     */
    private val processMarkUnread: (channelId: Long, messagesAfter: List<Message>) -> Int = { _, _ -> 0 },
) : StoreV2() {

    /**
     * One channel's read marker.
     *
     * [viewed] distinguishes "the user actually looked at this" from a marker written by
     * hydration or by sending a message; [isLockedAck] is the mark-unread flag described above.
     */
    data class Ack(val messageId: Long, val viewed: Boolean, val isLockedAck: Boolean) {
        constructor(readState: ModelReadState?, viewed: Boolean, isLockedAck: Boolean) :
            this(readState?.lastMessageId ?: 0L, viewed, isLockedAck)
    }

    /** Whether a channel is a thread, and whether that thread accepts acks. */
    sealed class ThreadAckState {
        object NotThread : ThreadAckState()
        data class Thread(val canAckThread: Boolean) : ThreadAckState()
    }

    /** A resolved ack awaiting its REST call. [EMPTY] means "nothing to do". */
    private data class PendingAck(val channelId: Long, val ack: Ack) {
        companion object {
            val EMPTY = PendingAck(0L, Ack(0L, false, false))
        }
    }

    private val acksPersister = Persister<Map<Long, Ack>>(ACKS_CACHE_KEY, HashMap())
    private var acksSnapshot: Map<Long, Ack> = acksPersister.get()
    private val acks: MutableMap<Long, Ack> = HashMap(acksSnapshot)

    private var threadSyncSubscription: Subscription? = null

    fun getAll(): Map<Long, Ack> = acksSnapshot

    @StoreThread
    fun getAllInternal(): Map<Long, Ack> = acks

    fun getForChannel(channelId: Long): Ack? = getAll()[channelId]

    fun observeAll(): Observable<Map<Long, Ack>> =
        observationDeck.connectRx(this) { getAll() }.distinctUntilChanged()

    fun observeForChannel(channelId: Long): Observable<Ack?> =
        observationDeck.connectRx(this) { getForChannel(channelId) }.distinctUntilChanged()

    /**
     * Acks [channelId], or every channel inside it when it is a category.
     *
     * [clearLock] is what "mark as read" passes to undo a previous mark-unread; without it the
     * locked ack would refuse the write.
     */
    @JvmOverloads
    fun ack(channelId: Long, isLockedAck: Boolean, clearLock: Boolean = false) = dispatcher.schedule {
        val channel = storeChannels.findChannelByIdInternal(channelId) ?: return@schedule
        if (channel.type != Channel.CATEGORY) {
            internalAck(channel.id, isLockedAck, clearLock)
        } else {
            storeChannels.findChannelsByCategoryInternal(channel.guildId, channel.id)
                .forEach { internalAck(it.id, isLockedAck, clearLock) }
        }
    }

    /**
     * Marks [channelId] unread from [messageId] onwards.
     *
     * The marker is moved to the newest message *strictly before* [messageId] so that message and
     * everything after it become unread again. When no earlier message is loaded, a synthetic
     * the DEX fallback expression is used verbatim.
     */
    fun markUnread(channelId: Long, messageId: Long) {
        Observable.combineLatest(
            storeMessages.observeMessagesForChannel(channelId).filter { it.isNotEmpty() },
            observeThreadAckState(channelId),
        ) { channelMessages, threadState -> channelMessages to threadState }
            .takeSingleUntilTimeout(MARK_UNREAD_TIMEOUT_MS, emitError = false)
            .computationLatest()
            .subscribe({ (channelMessages, threadState) ->
                if (threadState.isThreadAndUnableToAck()) return@subscribe
                dispatcher.schedule {
                    val newestBefore = channelMessages.filter { it.id < messageId }.maxByOrNull { it.id }
                    val messagesAfter = channelMessages.filter { it.id >= messageId }
                    val fallback = ((messageId ushr 22) - 1L - SnowflakeUtils.DISCORD_EPOCH) shl 22
                    val ack = Ack(newestBefore?.id ?: fallback, false, true)
                    updateAcks(channelId, ack)
                    val mentionCount = processMarkUnread(channelId, messagesAfter)
                    postPendingAck(Observable.just(PendingAck(channelId, ack)), mentionCount)
                }
            }, { })
    }

    /**
     * Starts the automatic ack pipeline.
     *
     * A channel is acked when the list is scrolled to the bottom, has finished loading, is not
     * detached (i.e. not showing a jumped-to window somewhere in the past) and has no locked ack.
     * Every one of those has to hold, which is why this is a combineLatest rather than a listener
     * on the scroll event alone.
     */
    fun init(
        selectedChannel: Observable<StoreChannelsSelected.ResolvedSelectedChannel>,
        chat: StoreChat,
        messagesLoader: StoreMessagesLoader,
    ) {
        val candidates = selectedChannel.distinctUntilChanged().switchMap { selected ->
            val channelId = selected.id
            if (channelId <= 0L) {
                Observable.just(0L)
            } else {
                Observable.combineLatest(
                    storeMessages.getAllDetached().debounce(50L, TimeUnit.MILLISECONDS),
                    messagesLoader.getMessagesLoadedState(channelId)
                        .map { !it.isLoadingMessages && it.isInitialMessagesLoaded }
                        .distinctUntilChanged(),
                    chat.observeInteractionState().filter { it.channelId == channelId },
                    observeForChannel(channelId),
                ) { detachedChannels, isLoadingSettled, interactionState, existingAck ->
                    val isAtBottom = interactionState.isAtBottomIgnoringTouch()
                    val isDetached = channelId in detachedChannels
                    val isManaged = selected.maybeChannel?.isManaged() == true
                    val isLocked = existingAck?.isLockedAck == true
                    val ackable = shouldAutoAck(
                        isAtBottom = isAtBottom,
                        isLoadingSettled = isLoadingSettled,
                        isDetached = isDetached,
                        isManaged = isManaged,
                        isLocked = isLocked,
                    )
                    if (ackable) interactionState.channelId else 0L
                }
            }
        }
        postPendingAck(getPendingAck(candidates.computationLatest().debounce(AUTO_ACK_DEBOUNCE_MS, TimeUnit.MILLISECONDS)))
    }

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        val readState = requireNotNull(payload.readState)
        if (!readState.isPartial) acks.clear()

        readState.entries.orEmpty().forEach { entry ->
            acks[entry.channelId] = Ack(entry, false, false)
        }

        // Channels whose newest message predates the user joining the guild were never unread;
        // seeding them avoids a wall of false badges on the first connect after joining.
        payload.guilds.orEmpty().forEach { guild ->
            val joinedAtMs = guild.members.orEmpty()
                .firstOrNull { it.user.id == payload.me?.id }
                ?.joinedAt
                ?.dateTimeMillis
                ?: 0L
            guild.channels.orEmpty()
                .filter { it.isTextOrVoiceChannel() && it.id !in acks && snowflakeTimestamp(it.lastMessageId) < joinedAtMs }
                .forEach { acks[it.id] = Ack(it.lastMessageId, false, false) }
        }

        pruneAcks()
        threadSyncSubscription?.unsubscribe()
        threadSyncSubscription = observeActiveThreadIdsWithPrevious()
            .computationLatest()
            .subscribe({ (oldThreadIds, newThreadIds) ->
                dispatcher.schedule { updateThreadAcks(oldThreadIds, newThreadIds) }
            }, { })
        markChanged()
    }

    @StoreThread
    fun handleGuildCreate() = pruneAcks()

    @StoreThread
    fun handleMessageAck(readState: ModelReadState) =
        updateAcks(readState.channelId, Ack(readState, false, false))

    /** Sending a message implicitly reads everything before it. */
    @StoreThread
    fun handleMessageCreate(message: com.discord.api.message.Message) {
        if (message.author?.id == meId()) {
            updateAcks(message.channelId, Ack(message.id, false, false))
        }
    }

    /**
     * A thread the user created counts as read in its parent channel.
     *
     * Only forum posts propagate: an ordinary thread does not move its parent's marker.
     */
    @StoreThread
    fun handleThreadCreateOrUpdate(channel: Channel) {
        if (!channel.isThread() || channel.isArchivedThread() || channel.ownerId != meId()) return
        val parent = storeChannels.getChannel(channel.parentId) ?: return
        if (parent.type == Channel.GUILD_FORUM) updateAcks(parent.id, Ack(channel.id, false, false))
    }

    /**
     * A selection transition releases mark-unread locks without moving the cursor or changing
     * whether that cursor was viewed.
     */
    @StoreThread
    fun handleChannelSelected() {
        acks.keys.toList().forEach { channelId ->
            acks[channelId]?.let { acks[channelId] = it.copy(isLockedAck = false) }
        }
        markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        threadSyncSubscription?.unsubscribe()
        threadSyncSubscription = null
    }

    /** Target account isolation: global Persister reset does not clear this live map. */
    @StoreThread
    fun clearForAccountIsolation() {
        acks.clear()
        acksSnapshot = emptyMap()
        acksPersister.clear(persist = true)
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        val snapshot = HashMap(acks)
        acksSnapshot = snapshot
        acksPersister.set(snapshot)
    }

    private fun internalAck(channelId: Long, isLockedAck: Boolean, clearLock: Boolean) =
        postPendingAck(
            getPendingAck(Observable.just(channelId), isLockedAck, clearLock)
                .takeSingleUntilTimeout(emitError = false),
        )

    /**
     * Turns a stream of "this channel may need acking" into concrete [PendingAck]s.
     *
     * Resolves the newest message and the thread state per channel and answers
     * [PendingAck.EMPTY] whenever the write would be rejected anyway, so the caller only has to
     * filter one sentinel rather than repeat the rules.
     */
    private fun getPendingAck(
        channelIds: Observable<Long>,
        isLockedAck: Boolean = false,
        clearLock: Boolean = false,
    ): Observable<PendingAck> = channelIds.switchMap { channelId ->
        if (channelId <= 0L) {
            Observable.just(PendingAck.EMPTY)
        } else {
            Observable.combineLatest(
                observeForChannel(channelId),
                storeMessagesMostRecent.observeRecentMessageIds(channelId),
                observeThreadAckState(channelId),
            ) { ack, mostRecentMessageId, threadState ->
                val ackable = threadState.isNonThreadChannel() || threadState.isThreadAndAbleToAck()
                val required = isLockedAck || isUpdateRequired(ack, mostRecentMessageId, clearLock)
                if (ackable && required) {
                    PendingAck(channelId, Ack(mostRecentMessageId, true, isLockedAck))
                } else {
                    PendingAck.EMPTY
                }
            }
        }
    }

    /**
     * Whether the marker actually needs to move.
     *
     * A locked ack blocks the automatic path unless [clearLock] was requested; otherwise the
     * write is only worth making when the newest message is ahead of the stored marker.
     */
    private fun isUpdateRequired(ack: Ack?, mostRecentMessageId: Long, clearLock: Boolean) =
        MessageUtils.isNewer(ack?.messageId, mostRecentMessageId) &&
            (clearLock || ack?.isLockedAck != true)

    /**
     * Writes the ledger locally, then posts. Local first, per the class comment.
     *
     * [mentionCount] is only non-zero for mark-unread, where the client already knows how many
     * mentions it is re-creating and tells the server rather than making it recompute.
     */
    private fun postPendingAck(
        pendingAcks: Observable<PendingAck>,
        mentionCount: Int = 0,
    ) {
        pendingAcks
            .filter { it != PendingAck.EMPTY }
            .computationLatest()
            .subscribe({ (channelId, ack) ->
                dispatcher.schedule {
                    updateAcks(channelId, ack)
                    ackRequest(channelId, ack.messageId, ack.isLockedAck, mentionCount)
                }
            }, { })
    }

    private fun observeThreadAckState(channelId: Long): Observable<ThreadAckState?> =
        observationDeck.connectRx<ThreadAckState?>(storeThreadsActiveJoined, storeThreadsActive) { threadAckState(channelId) }
            .distinctUntilChanged()

    /**
     * Applies an ack unless the channel is an un-ackable thread, or the write is a no-op.
     *
     * "Same message id but newly locked" is deliberately *not* a no-op: that is exactly the
     * mark-unread transition.
     */
    @StoreThread
    private fun updateAcks(channelId: Long, ackNewer: Ack) {
        if (threadAckState(channelId).isThreadAndUnableToAck()) return
        val existing = acks[channelId]
        val changed = existing == null ||
            MessageUtils.compareMessages(existing.messageId, ackNewer.messageId) != 0 ||
            (ackNewer.isLockedAck && !existing.isLockedAck)
        if (changed) {
            acks[channelId] = ackNewer
            markChanged()
        }
    }

    /** Drops markers for channels that no longer exist once unavailable guild hydration is complete. */
    @StoreThread
    private fun pruneAcks() {
        if (storeGuilds.getUnavailableGuildsInternal().isNotEmpty()) return
        val activeJoinedThreads = storeThreadsActiveJoined.getActiveJoinedThreadsInternal()
        val activeForumThreads = storeThreadsActive.getAllForumThreadsByIdInternal()
        val iterator = acks.keys.iterator()
        while (iterator.hasNext()) {
            val channelId = iterator.next()
            if (storeChannels.findChannelByIdInternal(channelId) == null &&
                channelId !in activeJoinedThreads && channelId !in activeForumThreads
            ) {
                iterator.remove()
                markChanged()
            }
        }
    }

    private fun observeActiveThreadIdsWithPrevious(): Observable<Pair<Set<Long>, Set<Long>>> =
        Observable.combineLatest(
            storeThreadsActiveJoined.observeAllActiveJoinedThreadsById(),
            storeThreadsActive.observeAllActiveForumThreadsById(),
        ) { joined, forum -> joined.keys + forum.keys }
            .leadingEdgeThrottle(1, TimeUnit.SECONDS)
            .distinctUntilChanged()
            .scan(emptySet<Long>() to emptySet<Long>()) { previous, threadIds -> previous.second to threadIds }

    @StoreThread
    private fun updateThreadAcks(oldThreadIds: Set<Long>, newThreadIds: Set<Long>) {
        if (storeGuilds.getUnavailableGuildsInternal().isEmpty()) {
            (oldThreadIds - newThreadIds).forEach { threadId ->
                if (acks.remove(threadId) != null) markChanged()
            }
        }
        val joined = storeThreadsActiveJoined.getActiveJoinedThreadsInternal()
        val forum = storeThreadsActive.getAllForumThreadsByIdInternal()
        val guildJoinedAt = storeGuilds.getGuildsJoinedAtInternal()
        (newThreadIds - oldThreadIds).forEach { threadId ->
            if (threadId in acks || !threadAckState(threadId).isThreadAndAbleToAck()) return@forEach
            val joinedThread = joined[threadId]
            val channel = joinedThread?.channel ?: forum[threadId] ?: return@forEach
            val guildTimestamp = guildJoinedAt[channel.guildId] ?: clock.currentTimeMillis()
            val threadTimestamp = joinedThread?.joinTimestamp?.dateTimeMillis?.minus(5_000L) ?: 0L
            val archiveTimestamp = TimeUtils.parseUTCDate(channel.threadMetadata?.archiveTimestamp)
            val channelTimestamp = maxOf(threadTimestamp, archiveTimestamp).takeIf { it != 0L }
                ?: TimeUtils.parseSnowflake(channel.id)
            val ackTimestamp = maxOf(guildTimestamp, channelTimestamp)
            acks[threadId] = Ack((ackTimestamp - SnowflakeUtils.DISCORD_EPOCH) shl 22, false, false)
            markChanged()
        }
    }

    @StoreThread
    private fun threadAckState(channelId: Long): ThreadAckState? {
        val channel = storeChannels.findChannelByIdInternal(channelId) ?: return null
        if (!channel.isThread()) return ThreadAckState.NotThread
        return ThreadAckState.Thread(
            channelId in storeThreadsActiveJoined.getActiveJoinedThreadsInternal() ||
                channelId in storeThreadsActive.getAllForumThreadsByIdInternal(),
        )
    }

    companion object {
        /** Persistence key; a version bump discards incompatible ledgers. */
        const val ACKS_CACHE_KEY = "MOST_RECENT_ACKS_V3"

        private const val AUTO_ACK_DEBOUNCE_MS = 500L
        private const val MARK_UNREAD_TIMEOUT_MS = 10L

        internal fun shouldAutoAck(
            isAtBottom: Boolean,
            isLoadingSettled: Boolean,
            isDetached: Boolean,
            isManaged: Boolean,
            isLocked: Boolean,
        ): Boolean = isAtBottom && isLoadingSettled && !isDetached && !isManaged && !isLocked


        fun ThreadAckState?.isNonThreadChannel(): Boolean = this is ThreadAckState.NotThread

        fun ThreadAckState?.isThreadAndAbleToAck(): Boolean =
            this is ThreadAckState.Thread && canAckThread

        fun ThreadAckState?.isThreadAndUnableToAck(): Boolean =
            this is ThreadAckState.Thread && !canAckThread

        private fun snowflakeTimestamp(snowflake: Long) = SnowflakeUtils.toTimestamp(snowflake)

        // The clamp is expressed as a clamp on the *timestamp* so that `fromTimestamp` can be
        // reused unchanged: below the epoch both forms yield 0.
    }
}
