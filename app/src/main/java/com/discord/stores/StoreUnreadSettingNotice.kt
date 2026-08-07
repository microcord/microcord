package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.notification.UnreadSetting
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.PublishSubject

/**
 * Promotes a channel the viewer clearly cares about to full unread badges.
 *
 * Reference `UnreadSettingNoticeStore2`. A channel left on mentions-only stays quiet even when the
 * viewer reads and posts in it every day, so once they have spent enough time or sent enough
 * messages the setting is raised for them and a notice offers to put it back. Newer channels need
 * less evidence than old ones, because interest in something just created is the stronger signal.
 *
 * Only runs for accounts carrying `USE_NEW_NOTIFICATIONS`; without it every channel already
 * resolves to [UnreadSetting.ALL_MESSAGES] and there is nothing to promote.
 */
class StoreUnreadSettingNotice(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val clock: Clock,
    private val cache: Persister<Map<Long, ChannelActivity>> = Persister(CACHE_KEY, emptyMap()),
) : StoreV2() {

    data class ChannelActivity(
        val lastActionMs: Long = 0L,
        val viewDurationMs: Long = 0L,
        val sends: Int = 0,
    )

    /** One rung of the ladder: below [maxAgeMs], this many sends or this much dwell is enough. */
    private data class Threshold(val maxAgeMs: Long, val sends: Int, val viewDurationMs: Long)

    private val activity = HashMap<Long, ChannelActivity>()
    private var activitySnapshot: Map<Long, ChannelActivity> = emptyMap()
    private val upgraded = HashSet<Long>()
    private val noticeSubject = PublishSubject.create<Long>()

    /** Emits the channel that was just promoted, for the undo notice. */
    fun observeNotices(): Observable<Long> = noticeSubject

    fun getActivity(): Map<Long, ChannelActivity> = activitySnapshot

    @StoreThread
    internal fun restore() {
        activity.putAll(cache.get())
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        activitySnapshot = activity.toMap()
        cache.set(activitySnapshot, persist = true)
    }

    /** Evidence older than a week says nothing about what the viewer cares about now. */
    @StoreThread
    fun handleConnectionOpen() {
        val cutoff = clock.currentTimeMillis() - MAX_STATE_AGE_MS
        upgraded.clear()
        if (activity.entries.removeAll { it.value.lastActionMs < cutoff }) markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        activity.clear()
        upgraded.clear()
        activitySnapshot = emptyMap()
        cache.clear(persist = true)
        markChanged()
    }

    fun handleMessageSent(channelId: Long) = record(channelId) { it.copy(sends = it.sends + 1) }

    /** Called on a [VIEW_TICK_MS] cadence while the channel is on screen. */
    fun handleChannelViewed(channelId: Long) = record(channelId) {
        it.copy(viewDurationMs = (it.viewDurationMs + VIEW_TICK_MS).coerceAtMost(MAX_VIEW_DURATION_MS))
    }

    private fun record(channelId: Long, update: (ChannelActivity) -> ChannelActivity) {
        if (channelId <= 0L) return
        dispatcher.schedule {
            if (channelId in upgraded) return@schedule
            val next = update(activity[channelId] ?: ChannelActivity())
                .copy(lastActionMs = clock.currentTimeMillis())
            activity[channelId] = next
            markChanged()
            maybeUpgrade(channelId, next)
        }
    }

    @StoreThread
    private fun maybeUpgrade(channelId: Long, current: ChannelActivity) {
        val channel = getChannel(channelId) ?: return
        if (!shouldTrack(channel)) return
        val threshold = thresholdFor(channel) ?: return
        if (current.sends < threshold.sends && current.viewDurationMs < threshold.viewDurationMs) return

        upgraded += channelId
        activity.remove(channelId)
        markChanged()
        applyUpgrade(channel)
        noticeSubject.onNext(channelId)
    }

    @StoreThread
    private fun shouldTrack(channel: Channel): Boolean {
        if (channel.id in upgraded) return false
        if (channel.guildId == 0L) return false
        if (!useNewNotifications()) return false
        if (isMuted(channel)) return false
        return hasNoOverride(channel) && resolvedUnreadSetting(channel) != UnreadSetting.ALL_MESSAGES
    }

    /**
     * The ladder is keyed on whichever is younger, the channel or the viewer's membership: a
     * long-standing channel in a guild joined yesterday is still new *to them*.
     */
    @StoreThread
    private fun thresholdFor(channel: Channel): Threshold? {
        val now = clock.currentTimeMillis()
        val channelAge = now - SnowflakeUtils.toTimestamp(channel.id)
        val membershipAge = guildJoinedAtMs(channel.guildId)?.let { now - it } ?: channelAge
        val age = minOf(channelAge, membershipAge)
        return THRESHOLDS.firstOrNull { age < it.maxAgeMs }
    }

    /** Wired by `StoreStream`; defaulted so the store stands alone in tests. */
    @JvmField var getChannel: (Long) -> Channel? = { null }

    @JvmField var useNewNotifications: () -> Boolean = { false }

    @JvmField var isMuted: (Channel) -> Boolean = { false }

    @JvmField var hasNoOverride: (Channel) -> Boolean = { true }

    @JvmField var resolvedUnreadSetting: (Channel) -> UnreadSetting = { UnreadSetting.ALL_MESSAGES }

    @JvmField var guildJoinedAtMs: (Long) -> Long? = { null }

    @JvmField var applyUpgrade: (Channel) -> Unit = {}

    companion object {
        const val CACHE_KEY = "STORE_UNREAD_SETTING_NOTICE_V1"

        /** How often the chat screen reports dwell time. */
        const val VIEW_TICK_MS = 15_000L

        private val MAX_VIEW_DURATION_MS = TimeUnit.MINUTES.toMillis(150)
        private val MAX_STATE_AGE_MS = TimeUnit.DAYS.toMillis(7)

        private val THRESHOLDS = listOf(
            Threshold(TimeUnit.HOURS.toMillis(1), sends = 1, viewDurationMs = TimeUnit.MINUTES.toMillis(1)),
            Threshold(TimeUnit.DAYS.toMillis(1), sends = 2, viewDurationMs = TimeUnit.MINUTES.toMillis(2)),
            Threshold(TimeUnit.DAYS.toMillis(7), sends = 5, viewDurationMs = TimeUnit.MINUTES.toMillis(5)),
            Threshold(TimeUnit.DAYS.toMillis(30), sends = 10, viewDurationMs = TimeUnit.MINUTES.toMillis(30)),
        )
    }
}
