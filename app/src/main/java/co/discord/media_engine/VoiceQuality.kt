package co.discord.media_engine

import kotlin.math.floor

class VoiceQuality {
    private var _inboundStats = linkedMapOf<String, InboundAudio>()
    private var _outboundStats = OutboundAudio(0, 0)
    private var _duration = Duration(0, 0, 0, 0)

    fun update(stats: Stats) {
        _duration.connected++
        val oldSent = _outboundStats.packetsSent
        val oldReceived = _inboundStats.values.sumOf { it.packetsReceived }
        _outboundStats = stats.outboundRtpAudio?.let { OutboundAudio(it.packetsSent, it.packetsLost) }
            ?: OutboundAudio(0, 0)

        stats.inboundRtpAudio.forEach { (id, current) ->
            val buffer = InboundBufferStats(
                current.audioJitterBuffer, current.audioJitterTarget, current.audioJitterDelay,
                current.relativeReceptionDelay, current.relativePlayoutDelay,
            )
            val frameOps = InboundFrameOpStats(
                current.opSilence, current.opNormal, current.opMerge, current.opExpand,
                current.opAccelerate, current.opPreemptiveExpand, current.opCNG,
            )
            val previous = _inboundStats[id]
            if (previous == null) {
                _inboundStats[id] = InboundAudio(
                    current.packetsReceived, current.packetsLost, 0.0, 0.0, 0,
                    Array(5) { 0 }, buffer, frameOps,
                )
            } else {
                val received = current.packetsReceived - previous.packetsReceived
                val lost = current.packetsLost - previous.packetsLost
                val mos = if (received > 0 && lost >= 0) {
                    calculateMos((stats.transport?.ping ?: 0) + current.jitterBuffer.toDouble(), lost.toDouble() / (received + lost))
                } else 0.0
                val buckets = previous.mosBuckets
                if (mos > 0) buckets[floor(mos).toInt()]++
                _inboundStats[id] = InboundAudio(
                    current.packetsReceived, current.packetsLost, mos, previous.mosSum + mos,
                    previous.mosCount + if (mos > 0) 1 else 0, buckets, buffer, frameOps,
                )
            }
        }

        val speaking = _outboundStats.packetsSent > oldSent
        val listening = _inboundStats.values.sumOf { it.packetsReceived } > oldReceived
        if (speaking) _duration.speaking++
        if (listening) _duration.listening++
        if (speaking || listening) _duration.participation++
    }

    fun getPacketStats(result: MutableMap<String, Any>) {
        result["packets_sent"] = _outboundStats.packetsSent
        result["packets_sent_lost"] = _outboundStats.packetsLost
        result["packets_received"] = _inboundStats.values.sumOf { it.packetsReceived }
        result["packets_received_lost"] = _inboundStats.values.sumOf { it.packetsLost }
    }

    fun getMosStats(result: MutableMap<String, Any>) {
        val count = _inboundStats.values.sumOf { it.mosCount }
        result["mos_mean"] = if (count > 0) _inboundStats.values.sumOf { it.mosSum } / count else 0
        val buckets = Array(5) { index -> _inboundStats.values.sumOf { it.mosBuckets[index] } }
        result["mos_1"] = buckets[1]
        result["mos_2"] = buckets[2]
        result["mos_3"] = buckets[3]
        result["mos_4"] = buckets[4]
    }

    fun getBufferStats(result: MutableMap<String, Any>) {
        val stats = _inboundStats.values.maxByOrNull { it.bufferStats.audioJitterBuffer?.p75 ?: 0.0 }?.bufferStats
        explode("audio_jitter_buffer", stats?.audioJitterBuffer, result)
        explode("audio_jitter_target", stats?.audioJitterTarget, result)
        explode("audio_jitter_delay", stats?.audioJitterDelay, result)
        explode("relative_reception_delay", stats?.relativeReceptionDelay, result)
        explode("relative_playout_delay", stats?.relativePlayoutDelay, result)
    }

    fun getFrameOpStats(result: MutableMap<String, Any>) {
        fun sum(value: (InboundFrameOpStats) -> Long?) = _inboundStats.values.mapNotNull { value(it.frameOpStats) }
            .takeIf { it.isNotEmpty() }?.sum()
        listOf(
            "silent" to sum { it.silent }, "normal" to sum { it.normal }, "merged" to sum { it.merged },
            "expanded" to sum { it.expanded }, "accelerated" to sum { it.accelerated },
            "preemptive_expanded" to sum { it.preemptiveExpanded }, "cng" to sum { it.cng },
        ).forEach { (name, value) -> if (value != null) result["frame_op_$name"] = value }
    }

    fun getDurationStats(result: MutableMap<String, Any>) {
        result["duration_listening"] = _duration.listening
        result["duration_speaking"] = _duration.speaking
        result["duration_participation"] = _duration.participation
        result["duration_connected"] = _duration.connected
    }

    private fun explode(key: String, metric: PlayoutMetric?, result: MutableMap<String, Any>) {
        result["${key}_mean"] = metric?.mean ?: 0
        result["${key}_p75"] = metric?.p75 ?: 0
        result["${key}_p95"] = metric?.p95 ?: 0
        result["${key}_p99"] = metric?.p99 ?: 0
        result["${key}_max"] = metric?.max ?: 0
    }

    private fun calculateMos(rtt: Double, lossRate: Double): Double {
        val r = 93.4 - (0.024 * rtt + if (rtt > 177.3) 0.11 * (rtt - 177.3) else 0.0) -
            (10.0 + 122.0 * lossRate / (lossRate + 10.0))
        return when {
            r < 0 -> 1.0
            r > 100 -> 4.5
            else -> 1.0 + 0.035 * r + 0.000007 * r * (r - 60) * (100 - r)
        }
    }
}
