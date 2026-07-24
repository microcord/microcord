package co.discord.media_engine

data class AudioInputDeviceDescription(val name: String, val guid: String)
data class AudioOutputDeviceDescription(val name: String, val guid: String)

enum class DeviceType { AudioInput, AudioOutput, VideoInput }
enum class MediaType { Audio, Video }

data class EncryptionSettings(val mode: String, val secretKey: IntArray)
data class RtcRegion(val region: String, val ips: Array<String>)

data class StreamParameters(
    val type: MediaType,
    val rid: String,
    val ssrc: Int,
    val rtxSsrc: Int,
    val active: Boolean,
    val maxBitrate: Int,
    val quality: Int,
    val maxPixelCount: Int,
)

data class AudioEncoder(
    val type: Int,
    val name: String,
    val freq: Int,
    val pacsize: Int,
    val channels: Int,
    val rate: Int,
)

data class AudioDecoder(
    val type: Int,
    val name: String,
    val freq: Int,
    val channels: Int,
    val params: Map<String, String>,
) {
    val paramsKeys = params.keys.toTypedArray()
    val paramsValues = params.values.toTypedArray()
}

data class VideoEncoder(
    val name: String,
    val type: Int,
    val rtxType: Int,
    val params: Map<String, String>,
) {
    val paramsKeys = params.keys.toTypedArray()
    val paramsValues = params.values.toTypedArray()
}

data class VideoDecoder(
    val name: String,
    val type: Int,
    val rtxType: Int,
    val params: Map<String, String>,
) {
    val paramsKeys = params.keys.toTypedArray()
    val paramsValues = params.values.toTypedArray()
}

data class StatsCodec(val id: Int, val name: String)
data class Resolution(val width: Int, val height: Int)
data class ReceiverReport(val id: String, val bitrate: Long, val fractionLost: Int)

data class PlayoutMetric(
    val last: Double,
    val mean: Double,
    val p75: Double,
    val p95: Double,
    val p99: Double,
    val max: Double,
)

data class Transport(
    val availableOutgoingBitrate: Int,
    val bytesReceived: Long,
    val bytesSent: Long,
    val ping: Int,
    val decryptionFailures: Int?,
    val localAddress: String?,
    val receiverReports: Array<ReceiverReport>?,
)

data class OutboundRtpAudio(
    val type: String,
    val ssrc: Long,
    val codec: StatsCodec,
    val bytesSent: Long,
    val packetsSent: Long,
    val packetsLost: Int,
    val fractionLost: Float,
    val audioLevel: Float,
    val audioDetected: Boolean,
    val framesCaptured: Long,
    val framesRendered: Long,
    val noiseCancellerIsEnabled: Boolean,
    val noiseCancellerProcessTime: Long,
    val voiceActivityDetectorIsEnabled: Boolean,
    val voiceActivityDetectorProcessTime: Long,
)

data class OutboundRtpVideo(
    val type: String,
    val ssrc: Long,
    val codec: StatsCodec,
    val bytesSent: Long,
    val packetsSent: Long,
    val packetsLost: Int,
    val fractionLost: Float,
    val bitrate: Int,
    val bitrateTarget: Int,
    val encodeUsage: Int,
    val encoderImplementationName: String,
    val averageEncodeTime: Int,
    val resolution: Resolution,
    val framesSent: Long,
    val framesEncoded: Long,
    val frameRateInput: Int,
    val frameRateEncode: Int,
    val firCount: Long,
    val nackCount: Long,
    val pliCount: Long,
    val qpSum: Long,
    val bandwidthLimitedResolution: Boolean,
    val cpuLimitedResolution: Boolean,
)

data class InboundRtpAudio(
    val type: String,
    val ssrc: Long,
    val codec: StatsCodec?,
    val bytesReceived: Long,
    val packetsReceived: Long,
    val packetsLost: Long,
    val audioLevel: Float,
    val audioDetected: Boolean,
    val jitter: Long,
    val jitterBuffer: Long,
    val jitterBufferPreferred: Long,
    val delayEstimate: Long,
    val decodingCNG: Int,
    val decodingMutedOutput: Int,
    val decodingNormal: Int,
    val decodingPLC: Int,
    val decodingPLCCNG: Int,
    val opSilence: Long?,
    val opNormal: Long?,
    val opMerge: Long?,
    val opExpand: Long?,
    val opAccelerate: Long?,
    val opPreemptiveExpand: Long?,
    val opCNG: Long?,
    val audioJitterBuffer: PlayoutMetric?,
    val audioJitterDelay: PlayoutMetric?,
    val audioJitterTarget: PlayoutMetric?,
    val audioPlayoutUnderruns: PlayoutMetric?,
    val relativeReceptionDelay: PlayoutMetric?,
    val relativePlayoutDelay: PlayoutMetric?,
)

data class InboundRtpVideo(
    val type: String,
    val ssrc: Long,
    val codec: StatsCodec?,
    val bytesReceived: Long,
    val packetsReceived: Int,
    val packetsLost: Int,
    val bitrate: Int,
    val averageDecodeTime: Int,
    val resolution: Resolution,
    val decoderImplementationName: String,
    val framesDecoded: Int,
    val framesDropped: Int,
    val framesReceived: Int,
    val frameRateDecode: Int,
    val frameRateNetwork: Int,
    val frameRateRender: Int,
    val firCount: Long,
    val nackCount: Long,
    val pliCount: Long,
    val qpSum: Long,
    val freezeCount: Long,
    val pauseCount: Long,
    val totalFreezesDuration: Long,
    val totalPausesDuration: Long,
    val totalFramesDuration: Long,
    val sumOfSquaredFramesDurations: Double,
)

data class Stats(
    val transport: Transport?,
    val outboundRtpAudio: OutboundRtpAudio?,
    val outboundRtpVideo: OutboundRtpVideo?,
    val inboundRtpAudio: Map<String, InboundRtpAudio>,
    val inboundRtpVideo: Map<String, InboundRtpVideo>,
)

data class InboundBufferStats(
    val audioJitterBuffer: PlayoutMetric?,
    val audioJitterTarget: PlayoutMetric?,
    val audioJitterDelay: PlayoutMetric?,
    val relativeReceptionDelay: PlayoutMetric?,
    val relativePlayoutDelay: PlayoutMetric?,
)

data class InboundFrameOpStats(
    val silent: Long?,
    val normal: Long?,
    val merged: Long?,
    val expanded: Long?,
    val accelerated: Long?,
    val preemptiveExpanded: Long?,
    val cng: Long?,
)

data class InboundAudio(
    val packetsReceived: Long,
    val packetsLost: Long,
    val mos: Double,
    val mosSum: Double,
    val mosCount: Int,
    val mosBuckets: Array<Int>,
    val bufferStats: InboundBufferStats,
    val frameOpStats: InboundFrameOpStats,
)

data class OutboundAudio(val packetsSent: Long, val packetsLost: Int)

data class Duration(
    var listening: Int,
    var speaking: Int,
    var participation: Int,
    var connected: Int,
)
