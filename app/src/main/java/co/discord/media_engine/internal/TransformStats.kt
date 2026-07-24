package co.discord.media_engine.internal

import co.discord.media_engine.InboundRtpAudio
import co.discord.media_engine.InboundRtpVideo
import co.discord.media_engine.OutboundRtpAudio
import co.discord.media_engine.OutboundRtpVideo
import co.discord.media_engine.PlayoutMetric
import co.discord.media_engine.Resolution
import co.discord.media_engine.Stats
import co.discord.media_engine.StatsCodec
import com.google.gson.Gson

object TransformStats {
    private val gson = Gson()

    @JvmStatic
    fun transform(stats: String): Stats {
        val native = gson.fromJson(stats, NativeStats::class.java)
        var bytesSent = 0L
        val outboundAudio = native.outbound?.audio?.let {
            bytesSent += it.bytesSent
            OutboundRtpAudio(
                "audio", it.ssrc, StatsCodec(it.codecPayloadType, it.codecName), it.bytesSent,
                it.packetsSent, it.packetsLost.coerceAtLeast(0), it.fractionLost * 100f,
                it.audioLevel / 32768f, it.speaking > 0, it.framesCaptured, it.framesRendered,
                it.noiseCancellerIsEnabled ?: false, it.noiseCancellerProcessTime ?: 0,
                it.voiceActivityDetectorIsEnabled ?: false, it.voiceActivityDetectorProcessTime ?: 0,
            )
        }

        val outboundVideoNative = native.outbound?.videos?.firstOrNull()
        val mediaSubstream = outboundVideoNative?.substreams?.firstOrNull { !it.isFlexFEC && !it.isRTX }
        val outboundVideo = if (outboundVideoNative != null && mediaSubstream != null) {
            val sentBytes = outboundVideoNative.substreams.sumOf { it.rtpStats.bytes() }
            bytesSent += sentBytes
            OutboundRtpVideo(
                "video", mediaSubstream.ssrc,
                StatsCodec(outboundVideoNative.codecPayloadType, outboundVideoNative.codecName),
                sentBytes, outboundVideoNative.substreams.sumOf { it.rtpStats.packets() },
                mediaSubstream.rtcpStats.packetsLost, mediaSubstream.rtcpStats.fractionLost.toFloat(),
                outboundVideoNative.mediaBitrate, outboundVideoNative.targetMediaBitrate,
                outboundVideoNative.encodeUsage, outboundVideoNative.encoderImplementationName,
                outboundVideoNative.avgEncodeTime, Resolution(mediaSubstream.width, mediaSubstream.height),
                (mediaSubstream.frameCounts.deltaFrames + mediaSubstream.frameCounts.keyFrames).toLong(),
                outboundVideoNative.framesEncoded, outboundVideoNative.inputFrameRate,
                outboundVideoNative.encodeFrameRate, mediaSubstream.rtcpStats.firPackets,
                mediaSubstream.rtcpStats.nackPackets, mediaSubstream.rtcpStats.pliPackets,
                outboundVideoNative.qpSum, outboundVideoNative.bwLimitedResolution,
                outboundVideoNative.cpuLimitedResolution,
            )
        } else null

        var bytesReceived = 0L
        val inboundAudio = linkedMapOf<String, InboundRtpAudio>()
        val inboundVideo = linkedMapOf<String, InboundRtpVideo>()
        native.inbound?.forEach { inbound ->
            val audio = inbound.audio
            bytesReceived += audio.bytesReceived
            val playout = inbound.playout?.milliseconds()
            inboundAudio[inbound.id] = InboundRtpAudio(
                "audio", audio.ssrc, StatsCodec(audio.codecPayloadType, audio.codecName),
                audio.bytesReceived, audio.packetsReceived, audio.packetsLost,
                audio.audioLevel / 32768f, audio.speaking > 0, audio.jitter, audio.jitterBuffer,
                audio.jitterBufferPreferred, audio.delayEstimate, audio.decodingCNG,
                audio.decodingMutedOutput, audio.decodingNormal, audio.decodingPLC,
                audio.decodingPLCCNG, audio.opSilence, audio.opNormal, audio.opMerge,
                audio.opExpand, audio.opAccelerate, audio.opPreemptiveExpand, audio.opCNG,
                playout?.audioJitterBuffer, playout?.audioJitterDelay, playout?.audioJitterTarget,
                playout?.audioPlayoutUnderruns, playout?.relativeReceptionDelay,
                playout?.relativePlayoutDelay,
            )

            inbound.video?.let { video ->
                val received = video.rtpStats.headerBytes + video.rtpStats.paddingBytes + video.rtpStats.payloadBytes
                bytesReceived += received
                inboundVideo[inbound.id] = InboundRtpVideo(
                    "video", video.ssrc,
                    video.codecPayloadType.takeUnless { it == -1 }?.let { StatsCodec(it, video.codecName) },
                    received, video.rtpStats.packets.toInt(), video.rtpStats.packetsLost,
                    video.totalBitrate, video.decode, Resolution(video.width, video.height),
                    video.decoderImplementationName, video.framesDecoded.toInt(),
                    video.framesDropped.toInt(), video.frameCounts.deltaFrames + video.frameCounts.keyFrames,
                    video.decodeFrameRate, video.networkFrameRate, video.renderFrameRate,
                    video.rtcpStats.firPackets, video.rtcpStats.nackPackets,
                    video.rtcpStats.pliPackets, video.qpSum.toLong(), video.freezeCount,
                    video.pauseCount, video.totalFreezesDuration, video.totalPausesDuration,
                    video.totalFramesDuration, video.sumOfSquaredFramesDurations,
                )
            }
        }

        val transport = native.transport?.let {
            co.discord.media_engine.Transport(
                it.sendBandwidth, bytesReceived, bytesSent, it.rtt, it.decryptionFailures.toInt(),
                it.localAddress, it.receiverReports,
            )
        } ?: co.discord.media_engine.Transport(0, bytesReceived, bytesSent, 0, 0, "", null)
        return Stats(transport, outboundAudio, outboundVideo, inboundAudio, inboundVideo)
    }

    private fun RtpStats.bytes() = listOf(fec, retransmitted, transmitted).sumOf {
        it.headerBytes + it.payloadBytes + it.paddingBytes
    }

    private fun RtpStats.packets() = fec.packets + retransmitted.packets + transmitted.packets

    private fun PlayoutMetric.milliseconds() = PlayoutMetric(
        Math.rint(last * 1000), Math.rint(mean * 1000), Math.rint(p75 * 1000),
        Math.rint(p95 * 1000), Math.rint(p99 * 1000), Math.rint(max * 1000),
    )

    private fun InboundPlayout.milliseconds() = copy(
        audioJitterBuffer = audioJitterBuffer.milliseconds(),
        audioJitterDelay = audioJitterDelay.milliseconds(),
        audioJitterTarget = audioJitterTarget.milliseconds(),
        audioPlayoutUnderruns = audioPlayoutUnderruns.milliseconds(),
        audioCaptureOverruns = audioCaptureOverruns.milliseconds(),
        videoJitterBuffer = videoJitterBuffer.milliseconds(),
        videoJitterDelay = videoJitterDelay.milliseconds(),
        videoJitterTarget = videoJitterTarget.milliseconds(),
        relativeReceptionDelay = relativeReceptionDelay.milliseconds(),
        relativePlayoutDelay = relativePlayoutDelay.milliseconds(),
    )
}
