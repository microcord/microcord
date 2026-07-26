package com.discord.rtcconnection.stats

import co.discord.media_engine.Stats
import co.discord.media_engine.VoiceQuality
import com.discord.app.AppLog
import com.discord.rtcconnection.KrispOveruseDetector
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription

/**
 * Polls the media engine once a second and fans the snapshot out to everything that derives from it.
 *
 * Polling rather than a push callback is the engine's own contract: `Connection.getStats` is a
 * request. The last [maxSamples] snapshots are kept so a disconnect report can quote the state just
 * before the failure rather than the empty state after it.
 *
 * Reference: `b.a.q.o0.d` (`RtcStatsCollector`).
 */
class RtcStatsCollector(
    private val connection: MediaEngineConnection,
    val voiceQuality: VoiceQuality,
    val videoQuality: VideoQualityTracker,
    private val krispOveruseDetector: KrispOveruseDetector,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
) {

    private val samples = ArrayDeque<Stats>()
    private var subscription: Subscription? = null

    /** The most recent snapshot, or `null` before the first poll returns. */
    val latestStats: Stats? get() = synchronized(samples) { samples.peekLast() }

    fun start() {
        synchronized(samples) { samples.clear() }
        subscription?.takeIf { !it.isUnsubscribed }?.let { stop() }
        subscription = Observable
            .interval(0L, intervalMs, TimeUnit.MILLISECONDS)
            .subscribe(
                { connection.getStats(::onStatsReceived) },
                { error -> AppLog.e(TAG, "RtcStatsCollector: Error collecting stats", error) },
            )
    }

    fun stop() {
        subscription?.unsubscribe()
        subscription = null
    }

    private fun onStatsReceived(stats: Stats) {
        synchronized(samples) {
            samples.addLast(stats)
            if (samples.size > maxSamples) samples.removeFirst()
        }
        voiceQuality.update(stats)
        videoQuality.update(stats)
        krispOveruseDetector.onStats(stats)
    }

    private companion object {
        private const val TAG = "RtcStatsCollector"
        private const val DEFAULT_INTERVAL_MS = 1_000L
        private const val DEFAULT_MAX_SAMPLES = 30
    }
}
