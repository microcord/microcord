package com.discord.rtcconnection

import co.discord.media_engine.OutboundRtpAudio
import co.discord.media_engine.Stats
import com.discord.rtcconnection.mediaengine.MediaEngineConnection

/**
 * Watches how much CPU the Krisp noise canceller and the voice-activity detector are spending, and
 * reports when the device cannot keep up.
 *
 * The measure is processing time per captured frame. A 10 ms Opus frame budget is 10 000 µs; the
 * reference trips at 8 µs/frame for noise cancellation and 4 µs/frame for VAD, i.e. long before the
 * pipeline actually underruns, so the client can switch models rather than ship glitched audio.
 *
 * A run of zero-delta samples means the canceller reported "enabled" but is not doing any work,
 * which is how a failed model load shows up; three in a row is reported as
 * [Status.FAILED].
 *
 * Only [MediaEngineConnection.Type.DEFAULT] connections are watched — a Go Live stream shares the
 * same capture pipeline and would double-count.
 *
 * Reference: `com.discord.rtcconnection.KrispOveruseDetector`.
 */
class KrispOveruseDetector(private val connection: MediaEngineConnection) {

    enum class Status { CPU_OVERUSE, FAILED, VAD_CPU_OVERUSE }

    private var lastVadStats: OutboundRtpAudio? = null
    private var lastNoiseCancellerStats: OutboundRtpAudio? = null
    private var consecutiveIdleSamples: Int = 0

    fun onStats(stats: Stats) {
        if (connection.type != MediaEngineConnection.Type.DEFAULT) return
        if (!connection.isNoiseCancellationEnabled()) return

        val outbound = stats.outboundRtpAudio ?: return

        if (outbound.noiseCancellerIsEnabled) {
            val previous = lastNoiseCancellerStats
            if (previous != null) {
                val (overused, elapsedNanos) = measure(
                    previous = previous,
                    current = outbound,
                    thresholdNanosPerFrame = NOISE_CANCELLER_THRESHOLD_NS_PER_FRAME,
                    processTime = OutboundRtpAudio::noiseCancellerProcessTime,
                )
                when {
                    overused -> connection.notifyKrispStatus(Status.CPU_OVERUSE)
                    elapsedNanos == 0L -> {
                        consecutiveIdleSamples++
                        if (consecutiveIdleSamples > MAX_IDLE_SAMPLES) {
                            connection.notifyKrispStatus(Status.FAILED)
                        }
                    }
                    else -> consecutiveIdleSamples = 0
                }
            }
            lastNoiseCancellerStats = outbound
        }

        if (outbound.voiceActivityDetectorIsEnabled) {
            val previous = lastVadStats
            if (previous != null) {
                val (overused, _) = measure(
                    previous = previous,
                    current = outbound,
                    thresholdNanosPerFrame = VAD_THRESHOLD_NS_PER_FRAME,
                    processTime = OutboundRtpAudio::voiceActivityDetectorProcessTime,
                )
                if (overused) connection.notifyKrispStatus(Status.VAD_CPU_OVERUSE)
            }
            lastVadStats = outbound
        }
    }

    /**
     * Returns whether processing time per captured frame exceeded [thresholdNanosPerFrame], plus
     * the raw elapsed processing time so the caller can tell "cheap" from "not running at all".
     *
     * A window with no captured frames is reported as not overused: dividing by zero frames would
     * make an idle microphone look like a stalled processor.
     */
    private fun measure(
        previous: OutboundRtpAudio,
        current: OutboundRtpAudio,
        thresholdNanosPerFrame: Double,
        processTime: (OutboundRtpAudio) -> Long,
    ): Pair<Boolean, Long> {
        val elapsedProcessTime = processTime(current) - processTime(previous)
        val framesCaptured = current.framesCaptured - previous.framesCaptured
        if (framesCaptured == 0L) return false to elapsedProcessTime
        return (elapsedProcessTime.toDouble() / framesCaptured > thresholdNanosPerFrame) to elapsedProcessTime
    }

    private companion object {
        private const val NOISE_CANCELLER_THRESHOLD_NS_PER_FRAME = 8.0
        private const val VAD_THRESHOLD_NS_PER_FRAME = 4.0
        private const val MAX_IDLE_SAMPLES = 2
    }
}
