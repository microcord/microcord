package com.discord.rtcconnection.stats

import co.discord.media_engine.InboundRtpVideo
import co.discord.media_engine.OutboundRtpVideo
import co.discord.media_engine.Stats
import com.discord.app.AppLog
import com.discord.utilities.time.Clock
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Accumulates per-stream video quality over the life of a connection, for the
 * `VIDEO_STREAM_ENDED` analytics event.
 *
 * The interesting part is not the totals but the *time spent* below each quality threshold: an
 * average bitrate hides a stream that was fine for nine minutes and unwatchable for one. Every
 * stats tick therefore adds the elapsed interval to each bucket the current measurement falls
 * under, giving a cumulative distribution rather than a mean.
 *
 * Bitrate and frame rate are measured over a three-sample window rather than sample-to-sample,
 * because a single 1 s tick straddling a keyframe swings wildly; resolution is taken from the
 * latest sample since it only changes when the encoder reconfigures.
 *
 * Three "the video was not really running" clocks are kept separately — paused, nobody watching,
 * and stopped — so a stream that nobody watched is not reported as a stream that was broken.
 *
 * Reference: `b.a.q.o0.e` (`VideoQualityTracker`). **Reduced reconstruction:** the reference also
 * folds in CPU and memory percentiles sampled by `DeviceResourceUsageMonitor`, which does not exist
 * in this project yet; the `client_performance_*` keys are therefore absent from the reports rather
 * than fabricated.
 */
class VideoQualityTracker(private val clock: Clock) {

    private val startTimeMs: Long = clock.currentTimeMillis()
    private var stopTimeMs: Long? = null

    private val pausedDuration = ToggleDuration(false, startTimeMs)
    private val zeroReceiversDuration = ToggleDuration(false, startTimeMs)
    private val videoStoppedDuration = ToggleDuration(false, startTimeMs)

    private val outbound = OutboundEntry()
    private val inboundByStream = linkedMapOf<String, Entry>()

    /** Video ssrc → when it was first seen, so time-to-first-frame can be measured per stream. */
    private val firstSeenBySsrc = linkedMapOf<Long, Long>()

    @Synchronized
    fun update(stats: Stats) {
        val now = clock.currentTimeMillis()
        val receiverCount = stats.transport?.receiverReports?.size ?: 0
        updateActivity(numReceivers = receiverCount, timestampMs = now)

        if (!videoStoppedDuration.isActive) {
            stats.outboundRtpVideo?.let { outboundVideo ->
                accumulate(outbound, outboundVideo.toSample(now))
                if (outbound.timeToFirstFrameMs == null && outboundVideo.framesEncoded > 0L) {
                    outbound.timeToFirstFrameMs = now - startTimeMs
                    AppLog.i(TAG, "outboundStats.timeToFirstFrame: ${outbound.timeToFirstFrameMs}")
                }
                accumulateTargets(outboundVideo.bitrateTarget)
            }
        }

        if (!pausedDuration.isActive) {
            stats.inboundRtpVideo.forEach { (streamKey, inboundVideo) ->
                val entry = inboundByStream.getOrPut(streamKey) { Entry() }
                accumulate(entry, inboundVideo.toSample(now))
                if (entry.timeToFirstFrameMs == null && inboundVideo.framesDecoded > 0) {
                    val firstSeen = firstSeenBySsrc[inboundVideo.ssrc]
                    if (firstSeen != null) {
                        entry.timeToFirstFrameMs = now - firstSeen
                        AppLog.i(TAG, "inbound.timeToFirstFrame: ${entry.timeToFirstFrameMs} (ssrc: ${inboundVideo.ssrc})")
                    } else {
                        AppLog.e(TAG, "inbound.timeToFirstFrame: Unable to locate start time. (ssrc: ${inboundVideo.ssrc})")
                    }
                }
            }
        }
    }

    /** Records when a remote video ssrc first appeared, so its first frame can be timed. */
    @Synchronized
    fun onVideoStreamStarted(videoSsrc: Long) {
        if (videoSsrc == 0L) return
        firstSeenBySsrc[videoSsrc] = clock.currentTimeMillis()
        AppLog.i(TAG, "handleVideoStreamUpdate(videoSsrc: $videoSsrc)")
    }

    /** Freezes the reported session duration; called when the connection is torn down. */
    @Synchronized
    fun stop() {
        stopTimeMs = clock.currentTimeMillis()
    }

    @Synchronized
    fun setTargetBitrate(bitrate: Int) {
        outbound.targetBitrateBps = bitrate
    }

    @Synchronized
    fun setTargetFrameRate(frameRate: Int) {
        outbound.targetFrameRate = frameRate
    }

    /** Stream keys with an inbound report, for enumerating receivers at teardown. */
    @Synchronized
    fun inboundStreamKeys(): List<String> = inboundByStream.keys.toList()

    /** Report for one receiver, or `null` when nothing was ever decoded for it. */
    @Synchronized
    fun inboundReport(streamKey: String): Map<String, Any>? =
        inboundByStream[streamKey]?.let(::buildReport)

    /** Report for what this client sent. */
    @Synchronized
    fun outboundReport(): Map<String, Any> {
        val activeSeconds = outbound.activeDurationMs / MILLIS_PER_SECOND
        val hasDuration = activeSeconds > 0f
        return buildReport(outbound) + mapOf(
            "target_bitrate_max" to if (hasDuration) (outbound.targetBitrateSecondsMax * BITS_PER_BYTE / activeSeconds).roundToLong() else 0L,
            "target_bitrate_network" to if (hasDuration) (outbound.targetBitrateSecondsNetwork * BITS_PER_BYTE / activeSeconds).roundToLong() else 0L,
            "target_fps" to if (hasDuration) (outbound.targetFrameRateSeconds / activeSeconds).roundToLong() else 0L,
        )
    }

    /**
     * Rolls the "is the video actually running" clocks forward.
     *
     * `videoStopped` is derived rather than set: it is true whenever the stream is paused *or*
     * nobody is receiving it. Crossing that boundary clears the outbound sample window, because
     * bitrate measured across a stop is meaningless.
     */
    private fun updateActivity(numReceivers: Int, timestampMs: Long) {
        zeroReceiversDuration.set(numReceivers == 0, timestampMs)
        val stopped = pausedDuration.isActive || zeroReceiversDuration.isActive
        if (stopped != videoStoppedDuration.isActive) {
            videoStoppedDuration.set(stopped, timestampMs)
            outbound.samples.clear()
        }
    }

    private fun accumulateTargets(bitrateTarget: Int) {
        val samples = outbound.samples
        if (samples.size < 2) return
        val intervalSeconds = (samples.last().timestampMs - samples[samples.size - 2].timestampMs) / MILLIS_PER_SECOND
        outbound.targetFrameRateSeconds += (outbound.targetFrameRate * intervalSeconds).roundToLong()
        outbound.targetBitrateSecondsNetwork += (bitrateTarget / BITS_PER_BYTE * intervalSeconds).roundToLong()
        outbound.targetBitrateSecondsMax += (outbound.targetBitrateBps / BITS_PER_BYTE * intervalSeconds).roundToLong()
    }

    private fun accumulate(entry: Entry, sample: Sample) {
        entry.samples += sample
        if (entry.samples.size < 2) return

        val last = entry.samples.last()
        val previous = entry.samples[entry.samples.size - 2]
        entry.activeDurationMs += last.timestampMs - previous.timestampMs

        val totals = entry.totals
        totals.frames += delta(last.counters.frames, previous.counters.frames)
        totals.framesTransferred += delta(last.counters.framesTransferred, previous.counters.framesTransferred)
        totals.packets += delta(last.counters.packets, previous.counters.packets)
        totals.packetsLost += delta(last.counters.packetsLost, previous.counters.packetsLost)
        totals.framesDropped += delta(last.counters.framesDropped, previous.counters.framesDropped)
        totals.bytes += delta(last.counters.bytes, previous.counters.bytes)
        totals.nacks += delta(last.counters.nacks, previous.counters.nacks)
        totals.plis += delta(last.counters.plis, previous.counters.plis)
        totals.qpSum += delta(last.counters.qpSum, previous.counters.qpSum)
        totals.freezeCount += delta(last.counters.freezeCount, previous.counters.freezeCount)
        // Verified against the shipped bytecode (`b/a/q/o0/e.a`, offsets 422-449): the release build
        // accumulates the *freeze* delta into the pause counter. Reproduced so `receiver_pause_count`
        // matches what the reference reported.
        totals.pauseCount += delta(last.counters.freezeCount, previous.counters.freezeCount)
        totals.totalFreezesDuration += delta(last.counters.totalFreezesDuration, previous.counters.totalFreezesDuration)
        totals.totalPausesDuration += delta(last.counters.totalPausesDuration, previous.counters.totalPausesDuration)
        totals.totalFramesDuration += delta(last.counters.totalFramesDuration, previous.counters.totalFramesDuration)

        val intervalSeconds = (last.timestampMs - previous.timestampMs) / MILLIS_PER_SECOND
        entry.resolutionSeconds += last.height * intervalSeconds

        if (entry.samples.size < WINDOW_SAMPLES) return

        val windowStart = entry.samples[entry.samples.size - 3]
        RESOLUTION_BUCKETS.forEach { height ->
            if (last.height <= height) entry.resolutionBuckets.add(height, intervalSeconds)
        }

        val windowSeconds = (last.timestampMs - windowStart.timestampMs) / MILLIS_PER_SECOND
        val averageBitrate = (last.counters.bytes - windowStart.counters.bytes) * BITS_PER_BYTE / windowSeconds
        val averageFps = (last.counters.frames - windowStart.counters.frames) / windowSeconds

        BITRATE_BUCKETS.forEach { threshold ->
            if (averageBitrate <= threshold) entry.bitrateBuckets.add(threshold, intervalSeconds)
        }
        FPS_BUCKETS.forEach { threshold ->
            if (averageFps <= threshold) entry.fpsBuckets.add(threshold, intervalSeconds)
        }

        entry.samples.removeAt(0)
    }

    private fun buildReport(entry: Entry): Map<String, Any> {
        val now = clock.currentTimeMillis()
        val sessionDurationSeconds = ((stopTimeMs ?: now) - startTimeMs) / MILLIS_PER_SECOND
        val activeSeconds = entry.activeDurationMs / MILLIS_PER_SECOND
        val hasDuration = activeSeconds > 0f

        return mapOf(
            "duration" to floor(sessionDurationSeconds.toDouble()),
            "avg_bitrate" to if (hasDuration) (entry.totals.bytes * BITS_PER_BYTE / activeSeconds).roundToLong() else 0L,
            "avg_fps" to if (hasDuration) (entry.totals.frames / activeSeconds).roundToLong() else 0L,
            "avg_resolution" to if (hasDuration) (entry.resolutionSeconds / activeSeconds).roundToLong() else 0L,
            "num_bytes" to entry.totals.bytes,
            "num_packets" to entry.totals.packets,
            "num_packets_lost" to entry.totals.packetsLost,
            "num_frames" to entry.totals.framesTransferred,
            "num_frames_dropped" to entry.totals.framesDropped,
            "num_nacks" to entry.totals.nacks,
            "num_plis" to entry.totals.plis,
            "qp_sum" to entry.totals.qpSum,
            "time_to_first_frame_ms" to (entry.timeToFirstFrameMs ?: 0L),
            "receiver_freeze_count" to entry.totals.freezeCount,
            "receiver_pause_count" to entry.totals.pauseCount,
            "receiver_total_freezes_duration" to entry.totals.totalFreezesDuration,
            "receiver_total_pauses_duration" to entry.totals.totalPausesDuration,
            "receiver_total_frames_duration" to entry.totals.totalFramesDuration,
            "num_pauses" to 0,
            "duration_paused" to (pausedDuration.totalMs(now) / MILLIS_PER_SECOND).roundToLong(),
            "duration_zero_receivers" to (zeroReceiversDuration.totalMs(now) / MILLIS_PER_SECOND).roundToLong(),
            "duration_video_stopped" to (videoStoppedDuration.totalMs(now) / MILLIS_PER_SECOND).roundToLong(),
        ) + bucketReport("duration_stream_", BITRATE_BUCKET_NAMES, entry.bitrateBuckets) +
            bucketReport("duration_fps_", FPS_BUCKET_NAMES, entry.fpsBuckets) +
            bucketReport("duration_resolution_under_", RESOLUTION_BUCKETS.associateWith { it.toString() }, entry.resolutionBuckets)
    }

    /** `Map.merge` is API 24; minSdk here is 21. */
    private fun MutableMap<Int, Float>.add(key: Int, seconds: Float) {
        this[key] = (this[key] ?: 0f) + seconds
    }

    private fun bucketReport(
        prefix: String,
        names: Map<Int, String>,
        buckets: Map<Int, Float>,
    ): Map<String, Any> = names.entries.associate { (threshold, name) ->
        "$prefix$name" to (buckets[threshold]?.roundToLong() ?: 0L)
    }

    /**
     * Counter deltas from the engine are normally monotonic, but a decoder reset restarts them at
     * zero. Treating that as a huge negative delta would poison every aggregate, so a non-increasing
     * sample contributes its raw value instead.
     */
    private fun delta(current: Long, previous: Long): Long = if (previous <= current) current - previous else current

    private fun InboundRtpVideo.toSample(timestampMs: Long) = Sample(
        height = resolution.height.toLong(),
        timestampMs = timestampMs,
        counters = Counters(
            frames = framesDecoded.toLong(),
            framesTransferred = framesReceived.toLong(),
            packets = packetsReceived.toLong(),
            packetsLost = packetsLost.toLong(),
            framesDropped = framesDropped.toLong(),
            bytes = bytesReceived,
            nacks = nackCount,
            plis = pliCount,
            qpSum = qpSum,
            freezeCount = freezeCount,
            pauseCount = pauseCount,
            totalFreezesDuration = totalFreezesDuration,
            totalPausesDuration = totalPausesDuration,
            totalFramesDuration = totalFramesDuration,
        ),
    )

    private fun OutboundRtpVideo.toSample(timestampMs: Long) = Sample(
        height = resolution.height.toLong(),
        timestampMs = timestampMs,
        counters = Counters(
            frames = framesEncoded,
            framesTransferred = framesSent,
            packets = packetsSent,
            packetsLost = packetsLost.toLong(),
            bytes = bytesSent,
            nacks = nackCount,
            plis = pliCount,
            qpSum = qpSum,
        ),
    )

    /** Raw engine counters at one instant. Inbound and outbound map onto the same slots. */
    private class Counters(
        var frames: Long = 0,
        var framesTransferred: Long = 0,
        var packets: Long = 0,
        var packetsLost: Long = 0,
        var framesDropped: Long = 0,
        var bytes: Long = 0,
        var nacks: Long = 0,
        var plis: Long = 0,
        var qpSum: Long = 0,
        var freezeCount: Long = 0,
        var pauseCount: Long = 0,
        var totalFreezesDuration: Long = 0,
        var totalPausesDuration: Long = 0,
        var totalFramesDuration: Long = 0,
    )

    private class Sample(val height: Long, val timestampMs: Long, val counters: Counters)

    private open class Entry {
        val samples: MutableList<Sample> = mutableListOf()
        val totals = Counters()
        var timeToFirstFrameMs: Long? = null
        var activeDurationMs: Long = 0
        var resolutionSeconds: Float = 0f
        val bitrateBuckets: MutableMap<Int, Float> = BITRATE_BUCKETS.associateWith { 0f }.toMutableMap()
        val fpsBuckets: MutableMap<Int, Float> = FPS_BUCKETS.associateWith { 0f }.toMutableMap()
        val resolutionBuckets: MutableMap<Int, Float> = RESOLUTION_BUCKETS.associateWith { 0f }.toMutableMap()
    }

    private class OutboundEntry : Entry() {
        /** Encoder ceiling from the last quality reconfiguration; the reference seeds 2.5 Mbps. */
        var targetBitrateBps: Int = DEFAULT_TARGET_BITRATE_BPS
        var targetFrameRate: Int = DEFAULT_TARGET_FRAME_RATE
        var targetFrameRateSeconds: Long = 0
        var targetBitrateSecondsNetwork: Long = 0
        var targetBitrateSecondsMax: Long = 0
    }

    /** Accumulates how long a boolean condition has held. */
    private class ToggleDuration(active: Boolean, currentTimeMs: Long) {
        private var activeSinceMs: Long? = if (active) currentTimeMs else null
        private var accumulatedMs: Long = 0

        val isActive: Boolean get() = activeSinceMs != null

        fun set(active: Boolean, currentTimeMs: Long) {
            val since = activeSinceMs
            if (since == null) {
                if (active) activeSinceMs = currentTimeMs
            } else if (!active) {
                accumulatedMs += currentTimeMs - since
                activeSinceMs = null
            }
        }

        fun totalMs(currentTimeMs: Long): Long =
            activeSinceMs?.let { accumulatedMs + currentTimeMs - it } ?: accumulatedMs
    }

    private companion object {
        private const val TAG = "VideoQuality"
        private const val MILLIS_PER_SECOND = 1000f
        private const val BITS_PER_BYTE = 8f
        private const val DEFAULT_TARGET_BITRATE_BPS = 2_500_000
        private const val DEFAULT_TARGET_FRAME_RATE = 30

        /** A bucket only counts once three samples are in hand; see the windowing note above. */
        private const val WINDOW_SAMPLES = 6

        private val BITRATE_BUCKETS = listOf(
            0, 500_000, 1_000_000, 1_500_000, 2_000_000, 3_000_000,
            4_000_000, 5_000_000, 6_000_000, 7_000_000, 8_000_000,
        )
        private val FPS_BUCKETS = listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60)
        private val RESOLUTION_BUCKETS = listOf(720, 480, 360)

        private val BITRATE_BUCKET_NAMES = mapOf(
            0 to "at_0mbps",
            500_000 to "under_0_5mbps",
            1_000_000 to "under_1mbps",
            1_500_000 to "under_1_5mbps",
            2_000_000 to "under_2mbps",
            3_000_000 to "under_3mbps",
            4_000_000 to "under_4mbps",
            5_000_000 to "under_5mbps",
            6_000_000 to "under_6mbps",
            7_000_000 to "under_7mbps",
            8_000_000 to "under_8mbps",
        )
        private val FPS_BUCKET_NAMES =
            mapOf(0 to "at_0") + FPS_BUCKETS.filter { it > 0 }.associateWith { "under_$it" }
    }
}
