package com.discord.utilities.analytics

import com.discord.analytics.generated.traits.TrackGuild
import com.discord.analytics.generated.traits.TrackGuildReceiver
import com.discord.api.channel.Channel
import com.discord.api.science.AnalyticsSchema
import com.discord.api.science.Science
import com.discord.rtcconnection.RtcConnection
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import rx.Observable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * The analytics event pipeline: queue, batching, throttling and session tracking data.
 *
 * `AnalyticsTracker` and `StoreAnalytics` are the two call-site surfaces; both funnel into
 * [Tracker]. Where the batch actually goes is decided by [AnalyticsSink], which is **disabled by
 * default** — see that file.
 */
object AnalyticsUtils {

    /**
     * Fills in the guild traits a schema-backed event cannot know on its own (`isMember`,
     * `isOwner`).
     *
     * `StoreAnalytics.init` installs this; before then, schemas keep whatever the call site set.
     * The reference reaches into `StoreStream` directly from `AnalyticsUtilsKt.fill`, which is not
     * possible here because the store graph's members are private to `StoreStream`.
     */
    @Volatile
    @JvmStatic
    var guildTraitResolver: ((TrackGuild) -> TrackGuild)? = null

    /**
     * Batches analytics events and hands full batches to the installed [AnalyticsSink].
     *
     * Events accumulate in [eventsQueue] and drain 1.5 s after the most recent one, so a burst
     * (opening a channel scrolls through several events) becomes one delivery. A batch is only
     * eligible to drain once the session has tracking data — either an analytics token from
     * `READY`, or a fingerprint for a logged-out session — otherwise the backend cannot attribute
     * it and the reference holds the events back.
     */
    class Tracker(
        private val clock: Clock = ClockFactory.get(),
        private val eventsQueue: ConcurrentLinkedQueue<Science.Event> = ConcurrentLinkedQueue(),
    ) {

        private var analyticsToken: String? = null
        private var fingerprinted: Boolean = false
        private val eventsThrottledUntilMillis = ConcurrentHashMap<Pair<String, Long?>, Long>()

        /**
         * Records the session's tracking data.
         *
         * Draining before and after the assignment is deliberate: events queued under the previous
         * session must be delivered with the previous token, and events that were held back for
         * lack of one become deliverable the moment it arrives.
         */
        @Synchronized
        fun setTrackingData(analyticsToken: String?, fingerprinted: Boolean = false) {
            drainEventsQueue()
            this.analyticsToken = analyticsToken
            this.fingerprinted = fingerprinted
            drainEventsQueue()
        }

        /** Tracks a schema-backed event. */
        fun track(analyticsSchema: AnalyticsSchema) {
            if (analyticsSchema is TrackGuildReceiver) {
                analyticsSchema.trackGuild = analyticsSchema.trackGuild
                    ?.let { guild -> guildTraitResolver?.invoke(guild) ?: guild }
            }
            track(Science.Event.SchemaObject(analyticsSchema))
        }

        /** Tracks a map-backed event. */
        @JvmOverloads
        fun track(event: String, properties: Map<String, Any>? = null) {
            track(Science.Event.MapObject(event, properties.orEmpty()))
        }

        /**
         * Tracks a map-backed event at most once per [throttleTimeMs] for the given [throttleKey].
         *
         * The properties are produced lazily so a throttled event costs nothing: several of these
         * events (`guild_viewed`, `channel_opened`) build a full guild/channel snapshot.
         */
        fun track(
            throttleKey: Pair<String, Long?>,
            throttleTimeMs: Long,
            lazyPropertyProvider: () -> Map<String, Any>,
        ) {
            if (isEventThrottled(throttleKey)) return
            track(throttleKey.first, lazyPropertyProvider())
            eventsThrottledUntilMillis[throttleKey] = clock.currentTimeMillis() + throttleTimeMs
        }

        fun track(event: Science.Event) {
            eventsQueue.add(event)
            Observable.timer(BATCH_DELAY_MS, TimeUnit.MILLISECONDS)
                .appSubscribe(errorTag = "AnalyticsUtils.Tracker") { drainEventsQueue() }
        }

        /** Drops everything queued but not yet delivered. Used on logout and by tests. */
        @Synchronized
        fun clear() {
            eventsQueue.clear()
            eventsThrottledUntilMillis.clear()
        }

        /** Visible for tests: how many events are waiting to be delivered. */
        internal fun queuedEventCount(): Int = eventsQueue.size

        @Synchronized
        private fun drainEventsQueue() {
            if (!canDrain) return
            val batch = eventsQueue.toList()
            eventsQueue.clear()
            AnalyticsSink.current.deliver(analyticsToken, batch) { eventsQueue.addAll(batch) }
        }

        private val canDrain: Boolean
            get() = eventsQueue.isNotEmpty() && (fingerprinted || isAuthed)

        private val isAuthed: Boolean
            get() = !analyticsToken.isNullOrBlank()

        private fun isEventThrottled(throttleKey: Pair<String, Long?>): Boolean =
            clock.currentTimeMillis() < (eventsThrottledUntilMillis[throttleKey] ?: 0L)

        companion object {
            /** Events raised within this window of each other are delivered as one batch. */
            private const val BATCH_DELAY_MS = 1_500L

            val instance: Tracker by lazy { Tracker() }
        }
    }
}

/*
 * The reference declares the two extension properties below as members of `AnalyticsUtils`. They
 * are top-level here so a caller can import them without also bringing the object into scope.
 */

/** Channel properties shared by every channel-scoped map event. */
internal val Channel.analyticsProperties: Map<String, Any>
    get() = mapOf(
        "channel_id" to id,
        "channel_type" to type,
        "channel_size_total" to (recipients?.size ?: 0),
    )

/**
 * The connection identifier shared by every voice map event.
 *
 * The reference hangs this off `RtcConnection` itself; the target's `RtcConnection` is still a
 * shell whose only member is [RtcConnection.Metadata], so it is defined on the metadata.
 */
internal val RtcConnection.Metadata.analyticsProperties: Map<String, Any>
    get() = mapOf("rtc_connection_id" to rtcConnectionId)
