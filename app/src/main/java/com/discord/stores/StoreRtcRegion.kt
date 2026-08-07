package com.discord.stores

import com.discord.models.domain.ModelRtcLatencyRegion
import com.discord.app.AppLog
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import java.util.Random
import java.util.concurrent.TimeUnit
import rx.Observable

/**
 * Persisted outcome of one RTC latency probe.
 *
 * [geoRankedRegions] is the server's own ordering at the time of the test; it is stored so a later
 * connect can tell "the server changed its mind about where I am" from "nothing changed".
 */
data class RtcLatencyTestResult(
    val latencyRankedRegions: List<String> = emptyList(),
    val geoRankedRegions: List<String> = emptyList(),
    val lastTestTimestampMs: Long = 0L,
)

/**
 * Which voice region this device should ask for, measured rather than guessed.
 *
 * The backend already geo-ranks regions; this store checks whether that ranking matches reality by
 * pinging each region's IPs through the media engine. The result is cached for a day and only
 * re-measured when the *geo* ranking itself changed, so a stationary device measures once.
 *
 * The probe runs on a random 1–30 s delay after connect. That jitter is deliberate: without it every
 * client that reconnects after an outage would probe the region endpoints at the same instant.
 */
class StoreRtcRegion(
    private val dispatcher: Dispatcher,
    private val clock: Clock,
    /** `GET` the region/IP list used for probing. `RestAPI.getRtcLatencyTestRegionsIps`. */
    private val fetchRegionsWithIps: () -> Observable<List<ModelRtcLatencyRegion>>,
    /** A current native engine is required because constructing one also initializes device audio. */
    private val isNativeEngineInitialized: () -> Boolean,
    /** `StoreMediaEngine.getRankedRtcRegions(regions, callback)`. */
    private val rankRegionsByLatency: (
        regions: List<ModelRtcLatencyRegion>,
        callback: (List<String>) -> Unit,
    ) -> Unit,
    private val random: Random = Random(),
) {

    private val lastTestResultCache =
        Persister(CACHE_KEY_LATENCY_TEST_RESULT, RtcLatencyTestResult())
    private var lastTestResult = RtcLatencyTestResult()

    fun init() {
        lastTestResult = lastTestResultCache.get()
    }

    @StoreThread
    internal fun handleConnectionOpen() {
        val jitterMs = (random.nextInt(
            MAX_LATENCY_TEST_CONN_OPEN_JITTER_MS - MIN_LATENCY_TEST_CONN_OPEN_JITTER_MS,
        ) + MIN_LATENCY_TEST_CONN_OPEN_JITTER_MS).toLong()
        Observable.timer(jitterMs, TimeUnit.MILLISECONDS)
            .subscribe({ fetchRtcLatencyTestRegionsIps() }, {})
    }

    private fun fetchRtcLatencyTestRegionsIps() {
        if (!isNativeEngineInitialized()) return
        fetchRegionsWithIps().subscribe(
            ::maybePerformLatencyTest,
            { AppLog.e(TAG, "Unable to fetch RTC latency test regions", it) },
        )
    }

    private fun maybePerformLatencyTest(regionsWithIps: List<ModelRtcLatencyRegion>) =
        dispatcher.schedule {
            // The probe list comes from outside the versioned API; an empty or ip-less response is a
            // failed fetch rather than a reason to re-rank against nothing.
            if (regionsWithIps.none { !it.ips.isNullOrEmpty() }) return@schedule
            val newGeoRankedRegions = regionsWithIps.map(ModelRtcLatencyRegion::region)
            val timeNowMs = clock.currentTimeMillis()
            if (!shouldPerformLatencyTest(newGeoRankedRegions, timeNowMs)) return@schedule
            rankRegionsByLatency(regionsWithIps) { latencyRankedRegions ->
                updateLastTestResult(
                    RtcLatencyTestResult(latencyRankedRegions, newGeoRankedRegions, timeNowMs),
                )
            }
        }

    /**
     * Re-measure only when there is nothing cached, the server's geo ranking changed (we probably
     * moved), or the cache is older than a day.
     */
    private fun shouldPerformLatencyTest(newGeoRankedRegions: List<String>, timeNowMs: Long): Boolean =
        lastTestResult.latencyRankedRegions.isEmpty() ||
            newGeoRankedRegions != lastTestResult.geoRankedRegions ||
            timeNowMs - lastTestResult.lastTestTimestampMs >= LATENCY_TEST_CACHE_TTL_MS

    private fun updateLastTestResult(lastTestResult: RtcLatencyTestResult) {
        this.lastTestResult = lastTestResult
        lastTestResultCache.set(lastTestResult, true)
    }

    @StoreThread
    fun getPreferredRegion(): String? = lastTestResult.latencyRankedRegions.firstOrNull()

    /**
     * @param guildId unused; the reference keeps the parameter so the call site does not have to
     *   know that region preference is currently global rather than per guild.
     */
    @StoreThread
    @Suppress("UNUSED_PARAMETER")
    fun shouldIncludePreferredRegion(guildId: Long?): Boolean = getPreferredRegion() != null

    private companion object {
        private const val TAG = "StoreRtcRegion"
        private const val CACHE_KEY_LATENCY_TEST_RESULT = "CACHE_KEY_LATENCY_TEST_RESULT"
        private const val LATENCY_TEST_CACHE_TTL_MS = 86_400_000L
        private const val MIN_LATENCY_TEST_CONN_OPEN_JITTER_MS = 1_000
        private const val MAX_LATENCY_TEST_CONN_OPEN_JITTER_MS = 30_000
    }
}
