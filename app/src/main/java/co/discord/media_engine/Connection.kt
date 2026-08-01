package co.discord.media_engine

import co.discord.media_engine.internal.TransformStats
import com.discord.app.AppLog
import com.discord.native.engine.NativeConnection
import com.discord.native.engine.NativeEngine
import com.google.gson.Gson
import com.hammerandchisel.libdiscord.Discord
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.VideoCapturer
import kotlin.math.floor
import kotlin.math.roundToLong

private val connectionGson = Gson()

private val floatStatsKeys = hashSetOf(
    "sumOfSquaredFramesDurations",
    "syncOffset",
    "targetDelay",
    "echoReturnLoss",
    "echoReturnLossEnchancement",
    "fractionLost",
    "residualEchoLikelihood",
    "residualEchoLikelihoodRecentMax",
)

private val nestedVideoKeys = arrayOf("rtpStats", "rtcpStats", "frameCounts")
private val nestedRtpKeys = arrayOf("transmitted", "retransmitted", "fec")

private data class NativeVideoStreamParameters(
    val type: String? = null,
    val rid: String? = null,
    val ssrc: Int = 0,
    val rtxSsrc: Int = 0,
    val active: Boolean = false,
    val maxBitrate: Int = 0,
    val quality: Int = 0,
    val maxPixelCount: Int = 0,
)

private data class ConnectionTransportOptions(
    val attenuateWhileSpeakingOthers: Boolean? = null,
    val attenuateWhileSpeakingSelf: Boolean? = null,
    val attenuation: Boolean? = null,
    val attenuationFactor: Double? = null,
    val audioDecoders: List<AudioDecoder>? = null,
    val audioEncoder: AudioEncoder? = null,
    val callBitRate: Int? = null,
    val callMaxBitRate: Int? = null,
    val callMinBitRate: Int? = null,
    val captureVideoFrameRate: Int? = null,
    val encodingVideoBitRate: Int? = null,
    val encodingVideoDegradationPreference: Int? = null,
    val encodingVideoFrameRate: Int? = null,
    val encodingVideoHeight: Int? = null,
    val encodingVideoMaxBitRate: Int? = null,
    val encodingVideoMinBitRate: Int? = null,
    val encodingVideoWidth: Int? = null,
    val encodingVoiceBitRate: Int? = null,
    val encryptionSettings: EncryptionSettings? = null,
    val fec: Boolean? = null,
    val inputMode: Int? = null,
    val inputModeOptions: InputModeOptions? = null,
    val minimumJitterBufferLevel: Int? = null,
    val packetLossRate: Float? = null,
    val postponeDecodeLevel: Int? = null,
    val prioritySpeakerDucking: Double? = null,
    val qos: Boolean? = null,
    val reconnectInterval: Int? = null,
    val remoteAudioHistoryMs: Int? = null,
    val selfMute: Boolean? = null,
    val streamParameters: List<Discord.NativeStreamParameters>? = null,
    val videoDecoders: List<VideoDecoder>? = null,
    val videoEncoder: VideoEncoder? = null,
) {
    data class InputModeOptions(
        val vadAutoThreshold: Int? = null,
        val vadDuringPreProcess: Boolean? = null,
        val vadUseKrisp: Boolean? = null,
        val vadThreshold: Float? = null,
        val vadLeading: Int? = null,
        val vadTrailing: Int? = null,
        val vadKrispActivationThreshold: Float? = null,
    )
}

@Suppress("unused")
class Connection(
    private val native: NativeConnection,
    streamParameters: List<Discord.NativeStreamParameters>,
    private val engine: NativeEngine,
    private val nativeContext: String? = null,
) {
    fun interface EncryptionModesCallback {
        fun onEncryptionModes(modes: Array<String?>?)
    }

    interface GetStatsCallback {
        fun onStats(stats: Stats)
        fun onStatsError(t: Throwable?)
    }

    fun interface OnVideoCallback {
        fun onVideo(userId: Long, ssrc: Int, streamIdentifier: String, streams: Array<StreamParameters>)
    }

    fun interface UserSpeakingStatusChangedCallback {
        fun onUserSpeakingStatusChanged(userId: Long, isUserSpeakingNow: Boolean, wantsPriority: Boolean)
    }

    object StatsFilter {
        const val ALL = -1
        const val TRANSPORT = 1
        const val OUTBOUND = 2
        const val INBOUND = 4
    }

    object SpeakingFlags {
        const val MICROPHONE = 1
        const val SOUNDSHARE = 2
        const val PRIORITY = 4
    }

    data class UserConnectionInfo(
        val id: String,
        val audioSsrc: Int,
        val ssrc: Int = audioSsrc,
        val videoSsrcs: List<Int>,
        val rtxSsrcs: List<Int>,
        val volume: Float,
        val mute: Boolean,
    )

    @Volatile
    private var disposed = false

    @Volatile
    private var lastMaxBitrate = Discord.DEFAULT_VIDEO_MAX_BITRATE

    @Volatile
    private var loggedStatsFailure = false

    init {
        set(
            ConnectionTransportOptions(
                encodingVideoDegradationPreference = 2,
                reconnectInterval = 60_000,
                callMaxBitRate = 10_000_000,
                callBitRate = 600_000,
                qos = false,
                attenuateWhileSpeakingSelf = false,
                callMinBitRate = 16_000,
                attenuation = false,
                fec = true,
                inputModeOptions = ConnectionTransportOptions.InputModeOptions(
                    vadAutoThreshold = 3,
                    vadDuringPreProcess = false,
                    vadUseKrisp = false,
                    vadThreshold = -60f,
                    vadLeading = 5,
                    vadTrailing = 25,
                    vadKrispActivationThreshold = 0.5f,
                ),
                prioritySpeakerDucking = 0.1,
                packetLossRate = 0.3f,
                minimumJitterBufferLevel = 80,
                inputMode = 1,
                attenuationFactor = 1.0,
                encodingVoiceBitRate = 96_000,
                postponeDecodeLevel = 100,
                attenuateWhileSpeakingOthers = true,
                selfMute = false,
                remoteAudioHistoryMs = 1_000,
                streamParameters = streamParameters,
            ),
        )
    }

    fun connectUser(
        userId: Long,
        audioSsrc: Int,
        videoSsrc: Int,
        rtxSsrc: Int,
        isMuted: Boolean,
        volume: Float,
    ) {
        if (disposed) return
        val hasVideo = videoSsrc != 0 && rtxSsrc != 0
        native.mergeUsers(
            connectionGson.toJson(
                listOf(
                    UserConnectionInfo(
                        id = userId.toString(),
                        audioSsrc = audioSsrc,
                        videoSsrcs = if (hasVideo) listOf(videoSsrc) else emptyList(),
                        rtxSsrcs = if (hasVideo) listOf(rtxSsrc) else emptyList(),
                        volume = volume.coerceIn(0f, 1f),
                        mute = isMuted,
                    ),
                ),
            ),
        )
    }

    fun deafenLocalUser(isDeafened: Boolean) {
        if (!disposed) native.setSelfDeafen(isDeafened)
    }

    fun disableVideo(userId: Long, isDisabled: Boolean) = Unit

    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        Discord.releaseStreamContext(nativeContext)
        native.dispose()
    }

    fun enableForwardErrorCorrection(enabled: Boolean) = Unit
    fun enableDiscontinuousTransmission(enabled: Boolean) = Unit

    fun getEncryptionModes(callback: EncryptionModesCallback?) {
        if (!disposed && callback != null) native.getEncryptionModes(callback::onEncryptionModes)
    }

    fun getStats(callback: GetStatsCallback) = getStats(callback, StatsFilter.ALL)

    fun getStats(callback: GetStatsCallback, filter: Int) {
        if (disposed) return
        native.getFilteredStats(filter) { statsJson ->
            try {
                callback.onStats(TransformStats.transform(sanitizeStats(statsJson)))
            } catch (failure: Throwable) {
                if (!loggedStatsFailure) {
                    loggedStatsFailure = true
                    AppLog.e(TAG, "Unable to transform native voice statistics")
                }
                callback.onStatsError(failure)
            }
        }
    }

    fun muteLocalUser(isMuted: Boolean) {
        if (disposed) return
        native.setSelfMute(isMuted)
        set(ConnectionTransportOptions(selfMute = isMuted))
    }

    fun muteUser(userId: Long, isMuted: Boolean) {
        if (!disposed) native.setLocalMute(userId.toString(), isMuted)
    }

    fun setAudioInputMode(mode: Int) = set(ConnectionTransportOptions(inputMode = mode))

    fun setCodecs(
        audioEncoder: AudioEncoder,
        videoEncoder: VideoEncoder,
        audioDecoders: Array<AudioDecoder>,
        videoDecoders: Array<VideoDecoder>,
    ) = set(
        ConnectionTransportOptions(
            audioEncoder = audioEncoder,
            videoEncoder = videoEncoder,
            audioDecoders = audioDecoders.toList(),
            videoDecoders = videoDecoders.toList(),
        ),
    )

    fun setEncodingQuality(minBitrate: Int, maxBitrate: Int, width: Int, height: Int, framerate: Int) {
        if (maxBitrate > 0) lastMaxBitrate = maxBitrate
        set(
            ConnectionTransportOptions(
                encodingVideoDegradationPreference = 2,
                encodingVideoBitRate = maxBitrate,
                encodingVideoMinBitRate = minBitrate,
                encodingVideoMaxBitRate = maxBitrate,
                encodingVideoWidth = width,
                encodingVideoHeight = height,
                encodingVideoFrameRate = framerate,
                captureVideoFrameRate = framerate,
            ),
        )
    }

    fun setEncryptionSettings(settings: EncryptionSettings) =
        set(ConnectionTransportOptions(encryptionSettings = settings))

    fun setExpectedPacketLossRate(lossRate: Float) =
        set(ConnectionTransportOptions(packetLossRate = lossRate.coerceIn(0f, 1f)))

    fun setOnVideoCallback(callback: OnVideoCallback) {
        if (disposed) return
        native.setOnVideoCallback { userId, ssrc, streamId, parametersJson ->
            val parameters = runCatching {
                connectionGson.fromJson(parametersJson, Array<NativeVideoStreamParameters>::class.java)
                    .orEmpty()
                    .map { value ->
                        StreamParameters(
                            type = if (value.type == "audio") MediaType.Audio else MediaType.Video,
                            rid = value.rid.orEmpty(),
                            ssrc = value.ssrc,
                            rtxSsrc = value.rtxSsrc,
                            active = value.active,
                            maxBitrate = when {
                                value.maxBitrate > 0 -> value.maxBitrate
                                value.quality in 1..99 -> lastMaxBitrate / 3
                                else -> lastMaxBitrate
                            },
                            quality = value.quality,
                            maxPixelCount = value.maxPixelCount,
                        )
                    }.toTypedArray()
            }.getOrElse {
                AppLog.e(TAG, "Unable to parse native video stream parameters")
                emptyArray()
            }
            callback.onVideo(userId.toLong(), ssrc.toInt(), streamId, parameters)
        }
    }

    fun setPTTActive(active: Boolean) {
        if (!disposed) native.setPTTActive(active, priority, false)
    }

    fun setUserPlayoutVolume(userId: Long, volume: Float) {
        if (!disposed) native.setLocalVolume(userId.toString(), volume)
    }

    fun setUserSpeakingStatusChangedCallback(callback: UserSpeakingStatusChangedCallback) {
        if (disposed) return
        native.setOnSpeakingCallback { userId, speakingFlags, _ ->
            callback.onUserSpeakingStatusChanged(
                userId.toLong(),
                speakingFlags and SpeakingFlags.MICROPHONE != 0,
                speakingFlags and SpeakingFlags.PRIORITY != 0,
            )
        }
    }

    fun setVADAutoThreshold(threshold: Int) =
        set(ConnectionTransportOptions.InputModeOptions(vadAutoThreshold = threshold))

    fun setVADLeadingFramesToBuffer(frameCount: Int) =
        set(ConnectionTransportOptions.InputModeOptions(vadLeading = frameCount))

    fun setVADTrailingFramesToSend(frameCount: Int) =
        set(ConnectionTransportOptions.InputModeOptions(vadTrailing = frameCount))

    fun setVADTriggerThreshold(threshold: Float) =
        set(ConnectionTransportOptions.InputModeOptions(vadThreshold = threshold))

    fun setVADUseKrisp(enabled: Boolean) =
        set(ConnectionTransportOptions.InputModeOptions(vadUseKrisp = enabled))

    fun setVideoBroadcast(enabled: Boolean) {
        if (!disposed) native.setVideoBroadcast(enabled)
    }

    fun startScreenshareBroadcast(capturer: VideoCapturer, nativeInstance: Long) {
        if (!disposed) native.startBroadcast(capturer, nativeInstance)
    }

    fun stopScreenshareBroadcast() {
        if (!disposed) native.stopBroadcast()
    }

    fun disconnectUser(userId: Long) = destroyUser(userId.toString())
    fun setMinimumPlayoutDelay(delayMs: Int) = setMinimumOutputDelay(delayMs)
    fun setQoS(enabled: Boolean) = set(ConnectionTransportOptions(qos = enabled))
    fun simulatePacketLoss(packetLossRate: Float) = setExpectedPacketLossRate(packetLossRate)

    fun setLocalPan(userId: Long, left: Float, right: Float) {
        if (!disposed) native.setLocalPan(userId.toString(), left, right)
    }

    fun setPingInterval(intervalMs: Int) {
        if (!disposed) native.setPingInterval(intervalMs)
    }

    fun setMinimumOutputDelay(delayMs: Int) {
        if (!disposed) native.setMinimumOutputDelay(delayMs)
    }

    fun configureConnectionRetries(baseDelayMs: Int, maxDelayMs: Int, maxAttempts: Int) {
        if (!disposed) native.configureConnectionRetries(baseDelayMs, maxDelayMs, maxAttempts)
    }

    fun setRawTransportOptions(optionsJson: String) {
        if (!disposed) native.setTransportOptions(optionsJson)
    }

    fun connectUsers(userIds: List<String>) {
        if (disposed) return
        native.mergeUsers(
            connectionGson.toJson(
                userIds.map { id ->
                    UserConnectionInfo(id, 0, videoSsrcs = emptyList(), rtxSsrcs = emptyList(), volume = 0f, mute = false)
                },
            ),
        )
    }

    fun destroyUser(userId: String) {
        if (!disposed) native.destroyUser(userId)
    }

    fun fastUdpReconnect() {
        if (!disposed) native.fastUdpReconnect()
    }

    fun getMLSKeyPackageB64(callback: NativeConnection.MLSKeyPackageCallback) {
        if (!disposed) native.getMLSKeyPackageB64(callback)
    }

    fun getMLSPairwiseFingerprintB64(
        version: Int,
        userId: String,
        callback: NativeConnection.MLSPairwiseFingerprintCallback,
    ) {
        if (!disposed) native.getMLSPairwiseFingerprintB64(version, userId, callback)
    }

    fun prepareMLSCommitTransitionB64(
        transitionId: Int,
        commit: String,
        callback: NativeConnection.MLSCommitTransitionCallback,
    ) {
        if (!disposed) native.prepareMLSCommitTransitionB64(transitionId, commit, callback)
    }

    fun prepareSecureFramesEpoch(epoch: String, transitionId: Int, groupId: String) {
        if (!disposed) native.prepareSecureFramesEpoch(epoch, transitionId, groupId)
    }

    fun prepareSecureFramesTransition(
        transitionId: Int,
        protocolVersion: Int,
        callback: NativeConnection.SecureFramesTransitionReadyCallback,
    ) {
        if (!disposed) native.prepareSecureFramesTransition(transitionId, protocolVersion, callback)
    }

    fun executeSecureFramesTransition(transitionId: Int) {
        if (!disposed) native.executeSecureFramesTransition(transitionId)
    }

    fun processMLSProposalsB64(proposals: String, callback: NativeConnection.MLSProcessProposalsCallback) {
        if (!disposed) native.processMLSProposalsB64(proposals, callback)
    }

    fun processMLSWelcomeB64(transitionId: Int, welcome: String, callback: NativeConnection.MLSWelcomeCallback) {
        if (!disposed) native.processMLSWelcomeB64(transitionId, welcome, callback)
    }

    fun setOnMLSFailureCallback(callback: NativeConnection.MLSFailureCallback) {
        if (!disposed) native.setOnMLSFailureCallback(callback)
    }

    fun setSecureFramesStateUpdateCallback(callback: NativeConnection.SecureFramesStateUpdateCallback) {
        if (!disposed) native.setSecureFramesStateUpdateCallback(callback)
    }

    fun updateMLSExternalSenderB64(externalSenderB64: String) {
        if (!disposed) native.updateMLSExternalSenderB64(externalSenderB64)
    }

    private fun set(options: ConnectionTransportOptions) {
        if (!disposed) native.setTransportOptions(connectionGson.toJson(options))
    }

    private fun set(options: ConnectionTransportOptions.InputModeOptions) =
        set(ConnectionTransportOptions(inputModeOptions = options))

    private fun sanitizeStats(stats: String): String {
        val root = JSONObject(stats)
        var changed = false
        root.optJSONArray("inbound")?.let { inbound ->
            for (index in inbound.length() - 1 downTo 0) {
                val entry = inbound.optJSONObject(index) ?: continue
                if (!entry.has("audio")) {
                    inbound.remove(index)
                    changed = true
                } else {
                    if (entry.remove("playout") != null) changed = true
                    if (normalizeInboundVideo(entry.optJSONObject("video"))) changed = true
                }
            }
        }
        root.optJSONObject("outbound")?.let { outbound ->
            if (normalizeOutboundVideos(outbound.optJSONArray("videos"))) changed = true
        }
        if (roundIntegralNumbers(root)) changed = true
        return if (changed) root.toString() else stats
    }

    private fun normalizeInboundVideo(video: JSONObject?): Boolean {
        video ?: return false
        var changed = video.backfillNames("decoderImplementationName")
        nestedVideoKeys.forEach { if (video.ensureObject(it)) changed = true }
        return changed
    }

    private fun normalizeOutboundVideos(videos: JSONArray?): Boolean {
        videos ?: return false
        var changed = false
        for (index in 0 until videos.length()) {
            val video = videos.optJSONObject(index) ?: continue
            if (video.backfillNames("encoderImplementationName")) changed = true
            val substreams = video.optJSONArray("substreams") ?: continue
            for (substreamIndex in 0 until substreams.length()) {
                val substream = substreams.optJSONObject(substreamIndex) ?: continue
                nestedVideoKeys.forEach { if (substream.ensureObject(it)) changed = true }
                val rtpStats = substream.getJSONObject("rtpStats")
                nestedRtpKeys.forEach { if (rtpStats.ensureObject(it)) changed = true }
            }
        }
        return changed
    }

    private fun JSONObject.backfillNames(implementationName: String): Boolean {
        var changed = false
        if (isNull(implementationName)) {
            put(implementationName, "")
            changed = true
        }
        if (isNull("codecName")) {
            put("codecName", "")
            changed = true
        }
        return changed
    }

    private fun JSONObject.ensureObject(key: String): Boolean {
        if (!isNull(key)) return false
        put(key, JSONObject())
        return true
    }

    private fun roundIntegralNumbers(value: Any?): Boolean {
        var changed = false
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                val fractionalKeys = ArrayList<String>()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (child is JSONObject || child is JSONArray) {
                        if (roundIntegralNumbers(child)) changed = true
                    } else if (child is Number && key !in floatStatsKeys) {
                        val number = child.toDouble()
                        if (number != floor(number) || number.isInfinite()) fractionalKeys += key
                    }
                }
                fractionalKeys.forEach { key -> value.put(key, value.optDouble(key).roundToLong()) }
                if (fractionalKeys.isNotEmpty()) changed = true
            }

            is JSONArray -> for (index in 0 until value.length()) {
                if (roundIntegralNumbers(value.opt(index))) changed = true
            }
        }
        return changed
    }

    companion object {
        @Volatile
        var priority = false

        private const val TAG = "MediaEngineConnection"
    }
}
