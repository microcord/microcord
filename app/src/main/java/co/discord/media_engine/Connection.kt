package co.discord.media_engine

import co.discord.media_engine.internal.TransformStats
import org.webrtc.VideoCapturer

class Connection private constructor(val nativeInstance: Long) {
    private var userSpeakingStatusChangedCallback: UserSpeakingStatusChangedCallback? = null

    interface EncryptionModesCallback {
        fun onEncryptionModes(modes: Array<String>)
    }

    interface GetStatsCallback {
        fun onStats(stats: Stats)
        fun onStatsError(t: Throwable?)
    }

    private class GetStatsCallbackNative(val callback: GetStatsCallback) {
        fun onStats(stats: String) {
            try {
                callback.onStats(TransformStats.transform(stats))
            } catch (t: Throwable) {
                callback.onStatsError(t)
            }
        }
    }

    interface OnVideoCallback {
        fun onVideo(userId: Long, ssrc: Int, streamIdentifier: String, streams: Array<StreamParameters>)
    }

    class StatsFilter {
        companion object {
            const val ALL = -1
            const val TRANSPORT = 1
            const val OUTBOUND = 2
            const val INBOUND = 4
        }
    }

    interface UserSpeakingStatusChangedCallback {
        fun onUserSpeakingStatusChanged(userId: Long, isUserSpeakingNow: Boolean, wantsPriority: Boolean)
    }

    private external fun getStatsNative(callback: GetStatsCallbackNative, filter: Int)

    external fun dispose()
    external fun simulatePacketLoss(packetLossRate: Float)
    external fun setQoS(enabled: Boolean)
    external fun setEncryptionSettings(settings: EncryptionSettings)
    external fun getEncryptionModes(callback: EncryptionModesCallback)
    external fun connectUser(userId: Long, audioSsrc: Int, videoSsrc: Int, rtxSsrc: Int, isMuted: Boolean, volume: Float)
    external fun disconnectUser(userId: Long)
    external fun setUserPlayoutVolume(userId: Long, volume: Float)
    external fun muteUser(userId: Long, willBeMuted: Boolean)
    external fun disableVideo(userId: Long, willBeDisabled: Boolean)
    external fun deafenLocalUser(willBeDeafened: Boolean)
    external fun muteLocalUser(willBeMuted: Boolean)
    external fun setMinimumPlayoutDelay(delayMs: Int)
    external fun enableForwardErrorCorrection(enabled: Boolean)
    external fun enableDiscontinuousTransmission(enabled: Boolean)
    external fun setExpectedPacketLossRate(expectedPacketLossRate: Float)
    external fun setCodecs(
        audioEncoder: AudioEncoder,
        videoEncoder: VideoEncoder,
        audioDecoders: Array<AudioDecoder>,
        videoDecoder: Array<VideoDecoder>,
    )
    external fun setOnVideoCallback(callback: OnVideoCallback)
    external fun setVideoBroadcast(broadcastVideo: Boolean)
    external fun startScreenshareBroadcast(capturer: VideoCapturer, audio: Long)
    external fun stopScreenshareBroadcast()
    external fun setAudioInputMode(mode: Int)
    external fun setVADLeadingFramesToBuffer(numFrames: Int)
    external fun setVADTrailingFramesToSend(numFrames: Int)
    external fun setVADTriggerThreshold(thresholdDb: Float)
    external fun setVADAutoThreshold(autoThreshold: Int)
    external fun setVADUseKrisp(useKrisp: Boolean)
    external fun setPTTActive(active: Boolean)
    external fun setEncodingQuality(minBitrate: Int, maxBitrate: Int, width: Int, height: Int, framerate: Int)

    fun setUserSpeakingStatusChangedCallback(callback: UserSpeakingStatusChangedCallback) {
        userSpeakingStatusChangedCallback = callback
    }

    fun getStats(callback: GetStatsCallback) = getStats(callback, StatsFilter.ALL)

    fun getStats(callback: GetStatsCallback, filter: Int) {
        getStatsNative(GetStatsCallbackNative(callback), filter)
    }
}
