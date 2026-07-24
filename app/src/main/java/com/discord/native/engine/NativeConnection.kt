package com.discord.native.engine

import org.webrtc.VideoCapturer

class NativeConnection {
    private val nativeInstance: Long = 0

    private external fun nativeDestroyInstance()

    fun dispose() = nativeDestroyInstance()

    external fun setDesktopSource(stringId: String, useVideoHook: Boolean, type: String)
    external fun configureConnectionRetries(baseDelayMs: Int, maxDelayMs: Int, maxAttempts: Int)
    external fun startBroadcast(capturer: VideoCapturer, soundshareNativeInstance: Long)
    external fun stopBroadcast()
    external fun getEncryptionModes(callback: GetEncryptionModesCallback)
    external fun setTransportOptions(optionsJSON: String)
    external fun mergeUsers(usersJSON: String)
    external fun destroyUser(userId: String)
    external fun setVideoBroadcast(broadcasting: Boolean)
    external fun setLocalPan(userId: String, left: Float, right: Float)
    external fun setLocalVolume(userId: String, volume: Float)
    external fun setLocalMute(userId: String, mute: Boolean)
    external fun setSelfMute(muted: Boolean)
    external fun setSelfDeafen(deafened: Boolean)
    external fun setMinimumOutputDelay(delay: Int)
    external fun setNoInputThreshold(threshold: Float)
    external fun setPTTActive(active: Boolean, priority: Boolean)
    external fun getStats(callback: GetStatsCallback)
    external fun setPingInterval(pingInterval: Int)
    external fun getFilteredStats(filter: Int, callback: GetStatsCallback)
    external fun setOnSpeakingCallback(callback: OnSpeakingCallback)
    external fun setOnPingCallback(callback: OnPingCallback)
    external fun setOnPingTimeoutCallback(callback: OnPingTimeoutCallback)
    external fun setOnVideoCallback(callback: OnVideoCallback)

    interface GetEncryptionModesCallback {
        fun onEncryptionModes(modes: Array<String>)
    }

    interface GetStatsCallback {
        fun onStats(stats: String)
    }

    interface OnPingCallback {
        fun onPing(ping: Int, server: String, port: Int, seq: Int)
    }

    interface OnPingTimeoutCallback {
        fun onPingTimeout(server: String, port: Int, seq: Int, timeout: Int)
    }

    interface OnSpeakingCallback {
        fun onSpeaking(userId: String, speakingFlags: Int)
    }

    interface OnVideoCallback {
        fun onVideo(userId: String, ssrc: Long, streamId: String, videoStreamParametersJson: String)
    }
}
