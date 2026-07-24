package co.discord.media_engine.internal

import co.discord.media_engine.PlayoutMetric
import co.discord.media_engine.ReceiverReport

typealias F32 = Float
typealias F64 = Double
typealias I16 = Int
typealias I32 = Int
typealias I64 = Long
typealias I8 = Int
typealias U16 = Int
typealias U32 = Long
typealias U64 = Long
typealias U8 = Int

data class NativeStats(
    val transport: Transport?,
    val outbound: Outbound?,
    val screenshare: Screenshare?,
    val inbound: Array<Inbound>?,
)

data class Transport(
    val decryptionFailures: Long,
    val maxPaddingBitrate: Int,
    val pacerDelay: Long,
    val rtt: Int,
    val sendBandwidth: Int,
    val localAddress: String,
    val receiverReports: Array<ReceiverReport>?,
)

data class Inbound(
    val id: String,
    val audio: InboundAudio,
    val video: InboundVideo?,
    val playout: InboundPlayout?,
)

data class Outbound(
    val id: String,
    val audio: OutboundAudio?,
    val videos: Array<OutboundVideo>?,
)

data class InboundAudio(
    val audioLevel: Int,
    val bytesReceived: Long,
    val codecName: String,
    val codecPayloadType: Int,
    val delayEstimate: Long,
    val decodingCNG: Int,
    val decodingMutedOutput: Int,
    val decodingNormal: Int,
    val decodingPLC: Int,
    val decodingPLCCNG: Int,
    val jitter: Long,
    val jitterBuffer: Long,
    val jitterBufferPreferred: Long,
    val packetsLost: Long,
    val packetsReceived: Long,
    val opSilence: Long,
    val opNormal: Long,
    val opMerge: Long,
    val opExpand: Long,
    val opAccelerate: Long,
    val opPreemptiveExpand: Long,
    val opCNG: Long,
    val speaking: Long,
    val ssrc: Long,
)

data class OutboundAudio(
    val audioLevel: Int,
    val bytesSent: Long,
    val codecName: String,
    val codecPayloadType: Int,
    val delayMedian: Int,
    val delayStd: Int,
    val echoReturnLoss: Double,
    val echoReturnLossEnchancement: Double,
    val fractionLost: Float,
    val jitter: Int,
    val packetsLost: Int,
    val packetsSent: Long,
    val residualEchoLikelihood: Double,
    val residualEchoLikelihoodRecentMax: Double,
    val rtt: Long,
    val speaking: Long,
    val ssrc: Long,
    val typingNoiseDetected: Boolean,
    val framesCaptured: Long,
    val framesRendered: Long,
    val noiseCancellerIsEnabled: Boolean?,
    val noiseCancellerProcessTime: Long?,
    val voiceActivityDetectorIsEnabled: Boolean?,
    val voiceActivityDetectorProcessTime: Long?,
)

data class InboundPlayout(
    val audioJitterBuffer: PlayoutMetric,
    val audioJitterDelay: PlayoutMetric,
    val audioJitterTarget: PlayoutMetric,
    val audioPlayoutUnderruns: PlayoutMetric,
    val audioCaptureOverruns: PlayoutMetric,
    val videoJitterBuffer: PlayoutMetric,
    val videoJitterDelay: PlayoutMetric,
    val videoJitterTarget: PlayoutMetric,
    val relativeReceptionDelay: PlayoutMetric,
    val relativePlayoutDelay: PlayoutMetric,
)

data class InboundVideo(
    val codecName: String,
    val codecPayloadType: Int,
    val currentDelay: Int,
    val currentPayloadType: Int,
    val decodeFrameRate: Int,
    val decode: Int,
    val decoderImplementationName: String,
    val frameCounts: FrameCounts,
    val framesDecoded: Long,
    val framesRendered: Long,
    val framesDropped: Int,
    val height: Int,
    val jitterBuffer: Int,
    val maxDecode: Int,
    val minPlayoutDelay: Int,
    val networkFrameRate: Int,
    val qpSum: Long,
    val renderDelay: Int,
    val renderFrameRate: Int,
    val rtcpStats: InboundRtcpStats,
    val rtpStats: InboundRtpStats,
    val ssrc: Long,
    val syncOffset: Float,
    val targetDelay: Float,
    val totalBitrate: Int,
    val width: Int,
    val freezeCount: Long,
    val pauseCount: Long,
    val totalFreezesDuration: Long,
    val totalPausesDuration: Long,
    val totalFramesDuration: Long,
    val sumOfSquaredFramesDurations: Double,
)

data class OutboundVideo(
    val codecName: String,
    val codecPayloadType: Int,
    val avgEncodeTime: Int,
    val bwLimitedResolution: Boolean,
    val cpuLimitedResolution: Boolean,
    val encodeFrameRate: Int,
    val encodeUsage: Int,
    val encoderImplementationName: String,
    val framesEncoded: Long,
    val inputFrameRate: Int,
    val mediaBitrate: Int,
    val numberOfCPUAdaptChanges: Int,
    val numberOfQualityAdaptChanges: Int,
    val qpSum: Long,
    val substreams: Array<Substream>,
    val suspended: Boolean,
    val targetMediaBitrate: Int,
)

data class FrameCounts(val deltaFrames: Int, val keyFrames: Int)
data class PacketStats(val headerBytes: Long, val packets: Long, val paddingBytes: Long, val payloadBytes: Long)
data class RtpStats(val fec: PacketStats, val retransmitted: PacketStats, val transmitted: PacketStats)

data class RtcpStats(
    val fractionLost: Int,
    val packetsLost: Int,
    val firPackets: Long,
    val nackPackets: Long,
    val nackRequests: Long,
    val pliPackets: Long,
    val uniqueNackRequests: Long,
)

data class InboundRtcpStats(
    val firPackets: Long,
    val nackPackets: Long,
    val nackRequests: Long,
    val pliPackets: Long,
    val uniqueNackRequests: Long,
)

data class InboundRtpStats(
    val packets: Long,
    val jitter: Long,
    val packetsLost: Int,
    val headerBytes: Long,
    val paddingBytes: Long,
    val payloadBytes: Long,
)

data class Substream(
    val avgDelay: Int,
    val frameCounts: FrameCounts,
    val height: Int,
    val isFlexFEC: Boolean,
    val isRTX: Boolean,
    val maxDelay: Int,
    val retransmitBitrate: Int,
    val rtcpStats: RtcpStats,
    val rtpStats: RtpStats,
    val ssrc: Long,
    val totalBitrate: Int,
    val width: Int,
)

data class Screenshare(
    val capturedFramesDropped: Int,
    val capturedFramesCount: Int,
    val capturedFramesMean: Int,
    val capturedFramesStdev: Int,
)
