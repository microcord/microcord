package com.discord.stores

import androidx.annotation.VisibleForTesting
import com.discord.gateway.io.GuildSubscriptions as GatewayGuildSubscriptions
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.rx.appSubscribe
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import java.util.concurrent.TimeUnit

/**
 * What the client has asked the gateway to stream for a guild.
 *
 * A guild is not streamed in full. After `READY` the socket sends almost nothing for a guild until
 * the client subscribes to the specific slices it is showing, so every field here corresponds to
 * one class of event the server will start sending:
 *
 * @property channels member-list windows, as `[start, end]` index ranges per channel
 * @property typing `TYPING_START`
 * @property activities presence and rich-presence updates
 * @property members individual users whose presence a screen is showing
 * @property threads `THREAD_*` events
 * @property threadMemberLists per-thread member lists
 *
 * Reference `com.discord.utilities.lazy.subscriptions.GuildSubscriptions`.
 */
data class GuildSubscriptions(
    val channels: Map<Long, List<IntRange>>? = null,
    val typing: Boolean? = null,
    val activities: Boolean? = null,
    val members: Set<Long>? = null,
    val threads: Boolean? = null,
    val threadMemberLists: List<Long>? = null,
) {
    /** The wire form of [channels]: the gateway wants two-element arrays, not ranges. */
    val serializedRanges: Map<Long, List<List<Int>>>? by lazy {
        channels?.mapValues { (_, ranges) -> ranges.map { listOf(it.first, it.last) } }
    }
}

/** One guild's subscription state, ready to be addressed to the socket. */
data class GuildSubscriptionPayload(
    val guildId: Long,
    val subscriptions: GuildSubscriptions,
) {
    /** Convenience for callers that only care about member-list windows. */
    constructor(guildId: Long, serializedChannels: Map<Long, List<List<Int>>>) : this(
        guildId,
        GuildSubscriptions(
            channels = serializedChannels.mapValues { (_, ranges) -> ranges.map { it[0]..it[1] } },
        ),
    )

    val channels: Map<Long, List<List<Int>>> get() = subscriptions.serializedRanges.orEmpty()

    fun toGatewayPayload(): GatewayGuildSubscriptions = GatewayGuildSubscriptions(
        channels = subscriptions.serializedRanges,
        typing = subscriptions.typing,
        activities = subscriptions.activities,
        members = subscriptions.members?.toList(),
        threads = subscriptions.threads,
        threadMemberLists = subscriptions.threadMemberLists,
    )
}

/**
 * Owns the guild subscription state and decides when it is pushed to the gateway.
 *
 * Mutations accumulate into a pending map and are emitted exactly once per dispatch cycle from
 * [snapshotData]. That batching matters: scrolling a member list produces a viewport change per
 * frame, and each one would otherwise be its own socket frame.
 *
 * Subscribing follows navigation. Selecting a guild subscribes to its typing, activity and thread
 * events; losing the connection drops everything except the selected guild and the guild the user
 * is in voice with, so a reconnect does not re-request state for guilds nobody is looking at.
 */
class StoreGuildSubscriptions(
    private val observationDeck: ObservationDeck,
    private val dispatcher: Dispatcher,
    /** Wire to `StoreGuildSelected::getSelectedGuildId`. */
    private val getSelectedGuildId: () -> Long = { 0L },
    /** Wire to the RTC connection's guild, so a voice session keeps its subscription. */
    private val getConnectedVoiceGuildId: () -> Long = { 0L },
    /** Fires for every guild whose subscriptions changed, once per dispatch. */
    private val onSubscription: (GuildSubscriptionPayload) -> Unit = {},
    unsubscribeDelayScheduler: Scheduler = Schedulers.computation(),
) : StoreV2() {

    private val subscriptionsManager = GuildSubscriptionsManager(
        onChange = { guildId, subscriptions -> emit(GuildSubscriptionPayload(guildId, subscriptions)) },
        delayScheduler = unsubscribeDelayScheduler,
    )

    private val events = PublishSubject.create<GuildSubscriptionPayload>()
    private var rangesSnapshot: Map<Long, Map<Long, List<IntRange>>> = emptyMap()

    fun get(guildId: Long): Map<Long, List<IntRange>> = rangesSnapshot[guildId].orEmpty()

    fun observe(guildId: Long): Observable<Map<Long, List<IntRange>>> =
        observationDeck.connectRx(this) { get(guildId) }.distinctUntilChanged()

    fun observeSubscriptions(): Observable<GuildSubscriptionPayload> = events

    // region navigation

    /** Subscribes the newly selected guild to typing, activities and threads. */
    @StoreThread
    fun handleGuildSelect(guildId: Long) {
        if (guildId <= 0L) return
        subscriptionsManager.subscribeTyping(guildId)
        subscriptionsManager.subscribeActivities(guildId)
        subscriptionsManager.subscribeThreads(guildId)
        markChanged()
    }

    @StoreThread
    fun handleGuildRemove(guildId: Long) {
        subscriptionsManager.remove(guildId)
        markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        subscriptionsManager.reset()
        markChanged()
    }

    @StoreThread
    fun reset() = handlePreLogout()

    /**
     * On reconnect the whole subscription set has to be replayed, because the new session starts
     * with none. On disconnect it is pruned to the two guilds that survive a backgrounded socket.
     */
    @StoreThread
    fun handleConnectionReady(isConnectionReady: Boolean) {
        if (isConnectionReady) {
            val selectedGuildId = getSelectedGuildId()
            if (selectedGuildId > 0L) handleGuildSelect(selectedGuildId)
            subscriptionsManager.queueExistingSubscriptions()
        } else {
            subscriptionsManager.retainAll(listOf(getSelectedGuildId(), getConnectedVoiceGuildId()))
        }
        markChanged()
    }

    // endregion

    // region member list windows

    /** Opening a member list requests the first page of it. */
    @StoreThread
    fun handlePanelOpened(guildId: Long, channelId: Long) =
        subscribeChannelRangeInternal(guildId, channelId, listOf(INITIAL_RANGE))

    @StoreThread
    fun handleViewportChanged(guildId: Long, channelId: Long, visibleRange: IntRange) =
        subscribeChannelRangeInternal(guildId, channelId, RangeComputer.computeRanges(visibleRange))

    @StoreThread
    fun handlePanelClosed(guildId: Long, channelId: Long) {
        if (guildId <= 0L || channelId <= 0L) return
        subscriptionsManager.unsubscribeChannel(guildId, channelId)
        markChanged()
    }

    fun subscribeChannelRange(guildId: Long, channelId: Long, range: IntRange) {
        if (guildId <= 0L || channelId <= 0L) return
        schedule {
            subscriptionsManager.subscribeChannel(guildId, channelId, RangeComputer.computeRanges(range))
            markChanged()
        }
    }

    @StoreThread
    private fun subscribeChannelRangeInternal(guildId: Long, channelId: Long, ranges: List<IntRange>) {
        if (guildId <= 0L || channelId <= 0L) return
        subscriptionsManager.subscribeChannel(guildId, channelId, ranges)
        markChanged()
    }

    // endregion

    // region individual members and threads

    /** Keeps a single user's presence live while a profile sheet or member row is on screen. */
    fun subscribeUser(guildId: Long, userId: Long) = schedule { handleSubscribeMember(guildId, userId) }

    fun unsubscribeUser(guildId: Long, userId: Long) = schedule { handleUnsubscribeMember(guildId, userId) }

    /** Applies a visible group in one dispatch, producing one batched member subscription. */
    fun updateUsers(guildId: Long, added: Set<Long>, removed: Set<Long>) {
        if (guildId <= 0L || (added.isEmpty() && removed.isEmpty())) return
        val subscribe = added.filter { it > 0L }
        val unsubscribe = removed.filter { it > 0L }
        schedule {
            subscribe.forEach { handleSubscribeMember(guildId, it) }
            unsubscribe.forEach { handleUnsubscribeMember(guildId, it) }
        }
    }

    @StoreThread
    fun handleSubscribeMember(guildId: Long, userId: Long) {
        if (guildId <= 0L) return
        subscriptionsManager.subscribeMember(guildId, userId)
        markChanged()
    }

    @StoreThread
    fun handleUnsubscribeMember(guildId: Long, userId: Long) {
        if (guildId <= 0L) return
        subscriptionsManager.unsubscribeMember(guildId, userId)
        markChanged()
    }

    fun subscribeThread(guildId: Long, threadId: Long) {
        if (guildId <= 0L || threadId <= 0L) return
        schedule {
            subscriptionsManager.subscribeThread(guildId, threadId)
            markChanged()
        }
    }

    // endregion

    @StoreThread
    override fun snapshotData() {
        rangesSnapshot = subscriptionsManager.channelRanges()
        // Draining here is what collapses a whole dispatch worth of viewport churn into one frame.
        subscriptionsManager.flush()
    }

    private fun emit(payload: GuildSubscriptionPayload) {
        events.onNext(payload)
        onSubscription(payload)
    }

    private fun schedule(block: () -> Unit) {
        dispatcher.schedule(block)
    }

    /**
     * Accumulates subscription changes and emits them in one batch.
     *
     * Reference `com.discord.utilities.lazy.subscriptions.GuildSubscriptionsManager` and its three
     * sub-managers. They live here because `utilities/lazy/subscriptions` is owned by another
     * wave; move them out when that package lands.
     */
    internal class GuildSubscriptionsManager(
        private val onChange: (Long, GuildSubscriptions) -> Unit,
        delayScheduler: Scheduler,
    ) {
        private val typingGuilds = HashSet<Long>()
        private val activityGuilds = HashSet<Long>()
        private val threadGuilds = HashSet<Long>()
        private val subscribedGuilds = HashSet<Long>()
        private val pendingEmissions = HashMap<Long, GuildSubscriptions>()

        private val channelSubscriptions = GuildChannelSubscriptionsManager { guildId, channels ->
            merge(guildId) { it.copy(channels = channels) }
        }
        private val threadSubscriptions = GuildThreadSubscriptionsManager { guildId, threads ->
            merge(guildId) { it.copy(threadMemberLists = threads) }
        }
        private val memberSubscriptions = GuildMemberSubscriptionsManager(
            onChange = { guildId, users, forceUpdate ->
                merge(guildId) { it.copy(members = users) }
                // An unsubscribe has no later trigger of its own, so it flushes immediately.
                if (forceUpdate) flush()
            },
            delayScheduler = delayScheduler,
        )

        @Synchronized
        fun get(guildId: Long) = GuildSubscriptions(
            channels = channelSubscriptions.get(guildId),
            typing = guildId in typingGuilds,
            activities = guildId in activityGuilds,
            members = memberSubscriptions.get(guildId),
            threads = guildId in threadGuilds,
            threadMemberLists = threadSubscriptions.get(guildId),
        )

        @Synchronized
        fun channelRanges(): Map<Long, Map<Long, List<IntRange>>> = channelSubscriptions.snapshot()

        @Synchronized
        fun flush() {
            if (pendingEmissions.isEmpty()) return
            val drained = pendingEmissions.toMap()
            pendingEmissions.clear()
            drained.forEach { (guildId, subscriptions) ->
                subscribedGuilds.add(guildId)
                onChange(guildId, subscriptions)
            }
        }

        /** Re-queues everything already subscribed, for replay after a reconnect. */
        @Synchronized
        fun queueExistingSubscriptions() {
            subscribedGuilds.forEach { pendingEmissions[it] = get(it) }
        }

        @Synchronized
        fun subscribeTyping(guildId: Long) {
            if (!typingGuilds.add(guildId)) return
            merge(guildId) { it.copy(typing = true) }
        }

        @Synchronized
        fun subscribeActivities(guildId: Long) {
            if (!activityGuilds.add(guildId)) return
            merge(guildId) { it.copy(activities = true) }
        }

        @Synchronized
        fun subscribeThreads(guildId: Long) {
            if (!threadGuilds.add(guildId)) return
            merge(guildId) { it.copy(threads = true) }
        }

        @Synchronized
        fun subscribeChannel(guildId: Long, channelId: Long, ranges: List<IntRange>) =
            channelSubscriptions.subscribe(guildId, channelId, ranges)

        @Synchronized
        fun unsubscribeChannel(guildId: Long, channelId: Long) =
            channelSubscriptions.unsubscribe(guildId, channelId)

        @Synchronized
        fun subscribeThread(guildId: Long, threadId: Long) = threadSubscriptions.subscribe(guildId, threadId)

        @Synchronized
        fun subscribeMember(guildId: Long, userId: Long) = memberSubscriptions.subscribe(guildId, userId)

        @Synchronized
        fun unsubscribeMember(guildId: Long, userId: Long) = memberSubscriptions.unsubscribe(guildId, userId)

        @Synchronized
        fun remove(guildId: Long) {
            subscribedGuilds.remove(guildId)
            pendingEmissions.remove(guildId)
            typingGuilds.remove(guildId)
            activityGuilds.remove(guildId)
            threadGuilds.remove(guildId)
            memberSubscriptions.remove(guildId)
            channelSubscriptions.remove(guildId)
            threadSubscriptions.remove(guildId)
        }

        @Synchronized
        fun reset() {
            subscribedGuilds.clear()
            pendingEmissions.clear()
            typingGuilds.clear()
            activityGuilds.clear()
            threadGuilds.clear()
            channelSubscriptions.reset()
            memberSubscriptions.reset()
            threadSubscriptions.reset()
        }

        @Synchronized
        fun retainAll(guildIds: List<Long>) {
            subscribedGuilds.retainAll(guildIds.toSet())
            pendingEmissions.keys.retainAll(guildIds.toSet())
            typingGuilds.retainAll(guildIds.toSet())
            activityGuilds.retainAll(guildIds.toSet())
            threadGuilds.retainAll(guildIds.toSet())
            channelSubscriptions.retainAll(guildIds)
            memberSubscriptions.retainAll(guildIds)
            threadSubscriptions.retainAll(guildIds)
        }

        private fun merge(guildId: Long, block: (GuildSubscriptions) -> GuildSubscriptions) {
            pendingEmissions[guildId] = block(pendingEmissions[guildId] ?: GuildSubscriptions())
        }
    }

    /**
     * Member-list windows, capped at [MAX_SUBSCRIBED_CHANNELS_PER_GUILD] channels per guild so
     * that browsing a server does not accumulate subscriptions for every channel visited.
     */
    internal class GuildChannelSubscriptionsManager(
        private val changeHandler: (Long, Map<Long, List<IntRange>>) -> Unit,
    ) {
        private val subscriptions = HashMap<Long, LruMap<Long, List<IntRange>>>()

        fun get(guildId: Long): Map<Long, List<IntRange>> = subscriptions[guildId]?.toMap().orEmpty()

        fun snapshot(): Map<Long, Map<Long, List<IntRange>>> = subscriptions.mapValues { it.value.toMap() }

        fun subscribe(guildId: Long, channelId: Long, ranges: List<IntRange>) {
            val guildSubscriptions = subscriptions.getOrPut(guildId) {
                LruMap(MAX_SUBSCRIBED_CHANNELS_PER_GUILD)
            }
            if (guildSubscriptions[channelId] == ranges) return
            guildSubscriptions[channelId] = ranges
            changeHandler(guildId, guildSubscriptions.toMap())
        }

        /** Closing a member list drops its window entirely rather than leaving it empty. */
        fun unsubscribe(guildId: Long, channelId: Long) {
            val guildSubscriptions = subscriptions[guildId] ?: return
            if (!guildSubscriptions.remove(channelId)) return
            changeHandler(guildId, guildSubscriptions.toMap())
        }

        fun remove(guildId: Long) { subscriptions.remove(guildId) }

        fun reset() = subscriptions.clear()

        fun retainAll(guildIds: List<Long>) = subscriptions.keys.retainAll(guildIds.toSet())

        private companion object {
            private const val MAX_SUBSCRIBED_CHANNELS_PER_GUILD = 5
        }
    }

    /** Per-thread member lists, capped at [MAX_SUBSCRIBED_THREADS_PER_GUILD]. */
    internal class GuildThreadSubscriptionsManager(
        private val changeHandler: (Long, List<Long>) -> Unit,
    ) {
        private val subscriptions = HashMap<Long, LruMap<Long, Long>>()

        fun get(guildId: Long): List<Long> = subscriptions[guildId]?.keys?.toList().orEmpty()

        fun subscribe(guildId: Long, threadId: Long) {
            val guildSubscriptions = subscriptions.getOrPut(guildId) {
                LruMap(MAX_SUBSCRIBED_THREADS_PER_GUILD)
            }
            if (guildSubscriptions[threadId] != null) return
            guildSubscriptions[threadId] = threadId
            changeHandler(guildId, guildSubscriptions.keys.toList())
        }

        fun remove(guildId: Long) { subscriptions.remove(guildId) }

        fun reset() = subscriptions.clear()

        fun retainAll(guildIds: List<Long>) = subscriptions.keys.retainAll(guildIds.toSet())

        private companion object {
            private const val MAX_SUBSCRIBED_THREADS_PER_GUILD = 3
        }
    }

    /**
     * Individual member subscriptions, reference counted.
     *
     * Several screens can want the same user at once, so a subscription is only really dropped
     * when the last holder releases it. Releases are also deferred by
     * [UNSUBSCRIBE_DELAY_SECONDS]: navigating between two screens that both want the same user
     * would otherwise unsubscribe and immediately resubscribe.
     */
    internal class GuildMemberSubscriptionsManager(
        private val onChange: (Long, Set<Long>, Boolean) -> Unit,
        private val delayScheduler: Scheduler,
    ) {
        private val subscriptions = HashMap<Long, MutableMap<Long, Int>>()
        private val pendingUnsubscriptions = HashMap<Long, MutableMap<Long, Int>>()
        private var delayedFlushSubscription: Subscription? = null

        fun get(guildId: Long): Set<Long> = subscriptions[guildId]?.keys.orEmpty()

        fun subscribe(guildId: Long, userId: Long) {
            val guildSubscriptions = subscriptions.getOrPut(guildId) { HashMap() }
            val count = (guildSubscriptions[userId] ?: 0) + 1
            guildSubscriptions[userId] = count
            if (count == 1) onChange(guildId, guildSubscriptions.keys.toSet(), false)
        }

        fun unsubscribe(guildId: Long, userId: Long) {
            if (subscriptions[guildId]?.get(userId) == null) return
            val pending = pendingUnsubscriptions.getOrPut(guildId) { HashMap() }
            val count = (pending[userId] ?: 0) + 1
            pending[userId] = count
            if (count == 1) triggerUnsubscribe()
        }

        fun remove(guildId: Long) {
            subscriptions.remove(guildId)
            pendingUnsubscriptions.remove(guildId)
        }

        fun reset() {
            subscriptions.clear()
            pendingUnsubscriptions.clear()
            delayedFlushSubscription?.unsubscribe()
            delayedFlushSubscription = null
        }

        fun retainAll(guildIds: List<Long>) = subscriptions.keys.retainAll(guildIds.toSet())

        private fun triggerUnsubscribe() {
            if (delayedFlushSubscription != null) return
            Observable.timer(UNSUBSCRIBE_DELAY_SECONDS, TimeUnit.SECONDS, delayScheduler)
                .appSubscribe(
                    GuildMemberSubscriptionsManager::class.java,
                    subscriptionHandler = { delayedFlushSubscription = it },
                ) { flushUnsubscriptions() }
        }

        private fun flushUnsubscriptions() {
            if (pendingUnsubscriptions.isEmpty()) return
            pendingUnsubscriptions.forEach { (guildId, released) ->
                val guildSubscriptions = subscriptions[guildId] ?: return@forEach
                var changed = false
                released.forEach { (userId, releaseCount) ->
                    val remaining = (guildSubscriptions[userId] ?: return@forEach) - releaseCount
                    if (remaining > 0) {
                        guildSubscriptions[userId] = remaining
                    } else {
                        guildSubscriptions.remove(userId)
                        changed = true
                    }
                }
                if (changed) {
                    if (guildSubscriptions.isEmpty()) subscriptions.remove(guildId)
                    onChange(guildId, get(guildId), true)
                }
            }
            delayedFlushSubscription = null
            pendingUnsubscriptions.clear()
        }

        private companion object {
            private const val UNSUBSCRIBE_DELAY_SECONDS = 20L
        }
    }

    /**
     * Insertion-ordered map that evicts its oldest entry past [maxSize].
     *
     * Written by composition rather than by overriding `LinkedHashMap.removeEldestEntry`, so the
     * eviction rule is visible at the call site and testable without a `Map` contract test.
     */
    internal class LruMap<K, V>(private val maxSize: Int) {
        private val entries = LinkedHashMap<K, V>()

        val keys: Set<K> get() = entries.keys

        operator fun get(key: K): V? = entries[key]

        operator fun set(key: K, value: V) {
            entries.remove(key)
            entries[key] = value
            while (entries.size > maxSize) {
                val iterator = entries.keys.iterator()
                iterator.next()
                iterator.remove()
            }
        }

        fun remove(key: K): Boolean = entries.remove(key) != null

        fun toMap(): Map<K, V> = LinkedHashMap(entries)
    }

    /**
     * Turns a visible index window into the fixed [DEFAULT_CHUNK_SIZE]-wide ranges the gateway
     * accepts.
     *
     * The first chunk is always included when the window does not already start at 0: the member
     * list header always shows the top of the list regardless of scroll position.
     */
    @VisibleForTesting
    internal object RangeComputer {
        private const val DEFAULT_CHUNK_SIZE = 100

        fun computeRanges(range: IntRange, chunkSize: Int = DEFAULT_CHUNK_SIZE): List<IntRange> {
            val ranges = ArrayList<IntRange>(3)
            val first = roundDownToNearestChunk(range.first, chunkSize)
            if (first > 0) ranges.add(0 until chunkSize)
            for (start in first until range.last step chunkSize) {
                ranges.add(start until start + chunkSize)
            }
            return ranges
        }

        private fun roundDownToNearestChunk(value: Int, chunkSize: Int): Int = (value / chunkSize) * chunkSize
    }

    companion object {
        /** The window a member list starts with before the user scrolls. */
        val INITIAL_RANGE: IntRange = 0 until 100

        @VisibleForTesting
        internal fun computeRanges(visibleRange: IntRange): List<IntRange> =
            RangeComputer.computeRanges(visibleRange)
    }
}
