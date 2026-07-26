package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.channel.isThread
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadState
import com.discord.models.message.Message
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.takeSingleUntilTimeout
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
    private val meId: () -> Long,
    /** Posts the ack. `StoreStream` owns the REST client, so the call is injected. */
    private val ackRequest: (channelId: Long, messageId: Long, isLockedAck: Boolean, mentionCount: Int) -> Unit,
    /**
     * Whether [channelId] is a thread and, if so, whether it can be acked.
     *
     * The reference reads `StoreThreadsActiveJoined` and `StoreThreadsActive`, neither of which
     * exists in this target yet. The default answers from [StoreChannels] alone, which is correct
     * for non-threads and conservative for threads (they are reported un-ackable). Bind this to
     * the real stores when they land — see the handoff notes.
     */
    private val threadAckState: (channelId: Long) -> ThreadAckState? = { channelId ->
        storeChannels.getChannel(channelId)?.let { channel ->
            if (channel.isThread()) ThreadAckState.Thread(canAckThread = false) else ThreadAckState.NotThread
        }
    },
    /**
     * True while any guild is still unavailable.
     *
     * [pruneAcks] must not run then: a channel missing only because its guild has not arrived
     * would have its read state deleted, and the badge would come back on the next connect.
     */
    private val hasUnavailableGuilds: () -> Boolean = { false },
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

    private var autoAckSubscription: Subscription? = null

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
        val channel = storeChannels.getChannel(channelId) ?: return@schedule
        if (channel.type != Channel.CATEGORY) {
            internalAck(channel.id, isLockedAck, clearLock)
        } else {
            storeChannels.getChannelsForGuild(channel.guildId).values
                .filter { it.parentId == channel.id }
                .forEach { internalAck(it.id, isLockedAck, clearLock) }
        }
    }

    /**
     * Marks [channelId] unread from [messageId] onwards.
     *
     * The marker is moved to the newest message *strictly before* [messageId] so that message and
     * everything after it become unread again. When no earlier message is loaded, a synthetic
     * snowflake one millisecond before [messageId] is used — the server only compares ordering, so
     * an id that never existed is fine and avoids a second round trip to find the real one.
     */
    fun markUnread(channelId: Long, messageId: Long) {
        Observable.combineLatest(
            storeMessages.observeMessagesForChannel(channelId),
            observeThreadAckState(channelId),
        ) { channelMessages, threadState -> channelMessages to threadState }
            .takeSingleUntilTimeout(MARK_UNREAD_TIMEOUT_MS, emitError = false)
            .computationLatest()
            .subscribe({ (channelMessages, threadState) ->
                if (threadState.isThreadAndUnableToAck()) return@subscribe
                dispatcher.schedule {
                    val newestBefore = channelMessages.filter { it.id < messageId }.maxByOrNull { it.id }
                    val messagesAfter = channelMessages.filter { it.id >= messageId }
                    val ack = Ack(newestBefore?.id ?: previousSnowflake(messageId), false, true)
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
        selectedChannelId: Observable<Long>,
        chat: StoreChat,
        messagesLoader: StoreMessagesLoader,
    ) {
        autoAckSubscription?.unsubscribe()
        val candidates = selectedChannelId.distinctUntilChanged().switchMap { channelId ->
            if (channelId <= 0L) {
                Observable.just(0L)
            } else {
                Observable.combineLatest(
                    storeMessages.getAllDetached(),
                    messagesLoader.getMessagesLoadedState(channelId)
                        .map { !it.isLoadingMessages && it.isInitialMessagesLoaded }
                        .distinctUntilChanged(),
                    chat.observeInteractionState().filter { it.channelId == channelId },
                    observeForChannel(channelId),
                ) { detachedChannels, isLoadingSettled, interactionState, existingAck ->
                    val ackable = interactionState.isAtBottomIgnoringTouch() &&
                        isLoadingSettled &&
                        channelId !in detachedChannels &&
                        existingAck?.isLockedAck != true
                    if (ackable) interactionState.channelId else 0L
                }
            }
        }
        postPendingAck(
            getPendingAck(candidates.computationLatest().debounce(AUTO_ACK_DEBOUNCE_MS, TimeUnit.MILLISECONDS)),
        )
    }

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        val readState = payload.readState
        if (readState?.isPartial != true) acks.clear()

        readState?.entries.orEmpty().forEach { entry ->
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
                .filter { it.id !in acks.keys && snowflakeTimestamp(it.lastMessageId) < joinedAtMs }
                .forEach { acks[it.id] = Ack(it.lastMessageId, false, false) }
        }

        pruneAcks()
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
        if (!channel.isThread() || channel.ownerId != meId()) return
        val parent = storeChannels.getChannel(channel.parentId) ?: return
        if (parent.type == Channel.GUILD_FORUM) updateAcks(parent.id, Ack(channel.id, false, false))
    }

    /**
     * Clears the `viewed` flag on every channel when the selection changes.
     *
     * `viewed` means "looked at during this visit"; leaving it set would make a channel the user
     * opened once look permanently viewed to the unread-badge logic.
     */
    @StoreThread
    fun handleChannelSelected() {
        acks.keys.toList().forEach { channelId ->
            acks[channelId]?.let { acks[channelId] = it.copy(viewed = false) }
        }
        markChanged()
    }

    /**
     * Reconciles the ledger with the set of threads that are currently active and joined.
     *
     * Threads that left the set lose their marker outright — an archived thread's read state is
     * meaningless and would otherwise pin a badge forever. Threads that joined the set get a
     * marker seeded from [seedTimestampMs] so their backlog does not all count as unread.
     */
    @StoreThread
    fun handleActiveThreadIdsChanged(
        oldThreadIds: Set<Long>,
        newThreadIds: Set<Long>,
        seedTimestampMs: (Long) -> Long? = { null },
    ) {
        if (!hasUnavailableGuilds()) {
            (oldThreadIds - newThreadIds).forEach { threadId ->
                if (acks.remove(threadId) != null) markChanged()
            }
        }
        (newThreadIds - oldThreadIds).forEach { threadId ->
            if (threadId in acks || !threadAckState(threadId).isThreadAndAbleToAck()) return@forEach
            val seed = seedTimestampMs(threadId) ?: return@forEach
            acks[threadId] = Ack(timestampToSnowflake(seed), false, false)
            markChanged()
        }
    }

    @StoreThread
    fun handlePreLogout() {
        autoAckSubscription?.unsubscribe()
        autoAckSubscription = null
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
    private fun isUpdateRequired(ack: Ack?, mostRecentMessageId: Long, clearLock: Boolean): Boolean = when {
        ack == null -> mostRecentMessageId > 0L
        ack.isLockedAck && !clearLock -> false
        else -> ack.messageId < mostRecentMessageId
    }

    /**
     * Writes the ledger locally, then posts. Local first, per the class comment.
     *
     * [mentionCount] is only non-zero for mark-unread, where the client already knows how many
     * mentions it is re-creating and tells the server rather than making it recompute.
     */
    private fun postPendingAck(pendingAcks: Observable<PendingAck>, mentionCount: Int = 0) {
        val subscription = pendingAcks
            .filter { it != PendingAck.EMPTY }
            .computationLatest()
            .subscribe({ (channelId, ack) ->
                dispatcher.schedule { updateAcks(channelId, ack) }
                ackRequest(channelId, ack.messageId, ack.isLockedAck, mentionCount)
            }, { })
        // Only the long-lived auto-ack pipeline is retained; one-shot acks complete on their own.
        if (mentionCount == 0) autoAckSubscription = subscription
    }

    private fun observeThreadAckState(channelId: Long): Observable<ThreadAckState?> =
        observationDeck.connectRx<ThreadAckState?>(this) { threadAckState(channelId) }
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
            existing.messageId != ackNewer.messageId ||
            (ackNewer.isLockedAck && !existing.isLockedAck)
        if (changed) {
            acks[channelId] = ackNewer
            markChanged()
        }
    }

    /** Drops markers for channels that no longer exist. See [hasUnavailableGuilds]. */
    @StoreThread
    private fun pruneAcks() {
        if (hasUnavailableGuilds()) return
        val iterator = acks.keys.iterator()
        while (iterator.hasNext()) {
            if (storeChannels.getChannel(iterator.next()) == null) {
                iterator.remove()
                markChanged()
            }
        }
    }

    companion object {
        /** Persistence key; a version bump discards incompatible ledgers. */
        const val ACKS_CACHE_KEY = "MOST_RECENT_ACKS_V3"

        private const val DISCORD_EPOCH = 1_420_070_400_000L
        private const val AUTO_ACK_DEBOUNCE_MS = 500L
        private const val MARK_UNREAD_TIMEOUT_MS = 10L

        fun ThreadAckState?.isNonThreadChannel(): Boolean = this is ThreadAckState.NotThread

        fun ThreadAckState?.isThreadAndAbleToAck(): Boolean =
            this is ThreadAckState.Thread && canAckThread

        fun ThreadAckState?.isThreadAndUnableToAck(): Boolean =
            this is ThreadAckState.Thread && !canAckThread

        private fun snowflakeTimestamp(snowflake: Long) = (snowflake ushr 22) + DISCORD_EPOCH

        private fun timestampToSnowflake(timestampMs: Long) =
            (timestampMs - DISCORD_EPOCH).coerceAtLeast(0L) shl 22

        /** The largest snowflake that sorts strictly before [snowflake]'s millisecond. */
        private fun previousSnowflake(snowflake: Long) = ((snowflake ushr 22) - 1L).coerceAtLeast(0L) shl 22
    }
}
