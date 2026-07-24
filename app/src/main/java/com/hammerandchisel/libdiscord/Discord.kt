package com.hammerandchisel.libdiscord

import android.content.Context
import co.discord.media_engine.AudioInputDeviceDescription
import co.discord.media_engine.AudioOutputDeviceDescription
import co.discord.media_engine.CameraEnumeratorProvider
import co.discord.media_engine.Connection
import co.discord.media_engine.RtcRegion
import co.discord.media_engine.SharedEglBaseContext
import co.discord.media_engine.StreamParameters
import co.discord.media_engine.VideoInputDeviceDescription
import com.discord.R
import org.webrtc.EglBase
import org.webrtc.VideoFrame

class Discord @JvmOverloads constructor(
    private val context: Context,
    logLevel: Int = LOGLEVEL_DEFAULT,
) {
    private var localVoiceLevelChangedCallback: LocalVoiceLevelChangedCallback? = null
    private var nativeInstance: Long

    init {
        krispVersion = context.getString(R.string.krisp_model_version)
        CameraEnumeratorProvider.maybeInit(context)
        nativeInstance = nativeConstructor(context, SharedEglBaseContext.getEglContext(), logLevel)
    }

    private external fun nativeConstructor(context: Context, eglContext: EglBase.Context, logLevel: Int): Long
    private external fun setLocalVoiceLevelChangedCallbackNative(enabled: Boolean)

    external fun connectToServer(
        ssrc: Int,
        userId: Long,
        ip: String,
        port: Int,
        streams: Array<StreamParameters>,
        callback: ConnectToServerCallback,
    ): Connection

    external fun crash()
    external fun dispose()

    @Deprecated("Use the callback overload")
    external fun enableBuiltInAEC(enabled: Boolean)

    external fun enableBuiltInAEC(enabled: Boolean, callback: BuiltinAECCallback?)
    external fun getAudioInputDevices(callback: GetAudioInputDevicesCallback)
    external fun getAudioOutputDevices(callback: GetAudioOutputDevicesCallback)
    external fun getAudioSubsystem(callback: GetAudioSubsystemCallback)
    external fun getRankedRtcRegions(regions: Array<RtcRegion>, callback: GetRankedRtcRegionsCallback)
    external fun getSupportedVideoCodecs(callback: GetSupportedVideoCodecsCallback)
    external fun getVideoInputDevices(callback: GetVideoInputDevicesCallback)
    external fun setAudioInputEnabled(enabled: Boolean)
    external fun setAutomaticGainControl(enabled: Boolean)

    @Deprecated("Use the callback overload")
    external fun setEchoCancellation(enabled: Boolean)

    external fun setEchoCancellation(enabled: Boolean, mobileMode: Boolean, callback: AecConfigCallback?)
    external fun setKeepAliveChannel(enabled: Boolean)
    external fun setMicVolume(volume: Float)
    external fun setNoAudioInputCallback(callback: NoAudioInputCallback)
    external fun setNoAudioInputThreshold(threshold: Float)
    external fun setNoiseCancellation(enabled: Boolean)
    external fun setNoiseSuppression(enabled: Boolean)
    external fun setPlayoutDevice(index: Int)
    external fun setRecordingDevice(index: Int)
    external fun setSpeakerVolume(volume: Float)
    external fun setVideoInputDevice(index: Int)
    external fun setVideoOutputSink(streamId: String, callback: VideoFrameCallback?)
    external fun signalVideoOutputSinkReady(streamId: String)

    fun setLocalVoiceLevelChangedCallback(callback: LocalVoiceLevelChangedCallback?) {
        localVoiceLevelChangedCallback = callback
        setLocalVoiceLevelChangedCallbackNative(callback != null)
    }

    fun interface AecConfigCallback {
        fun onConfigureAEC(
            requestEnable: Boolean,
            requestMobileMode: Boolean,
            enabled: Boolean,
            previouslyEnabled: Boolean,
            previouslyMobileMode: Boolean,
        )
    }

    fun interface BuiltinAECCallback {
        fun onConfigureBuiltinAEC(requested: Boolean, nativeSupported: Boolean, enabled: Boolean)
    }

    fun interface ConnectToServerCallback {
        fun onConnectToServer(connectionInfo: ConnectionInfo, errorMessage: String)
    }

    class ConnectionInfo(
        @JvmField var isConnected: Boolean,
        @JvmField var protocol: String,
        @JvmField var localAddress: String,
        @JvmField var localPort: Int,
    )

    fun interface GetAudioInputDevicesCallback {
        fun onDevices(devices: Array<AudioInputDeviceDescription>)
    }

    fun interface GetAudioOutputDevicesCallback {
        fun onDevices(devices: Array<AudioOutputDeviceDescription>)
    }

    fun interface GetAudioSubsystemCallback {
        fun onAudioSubsystem(audioSubsystem: String, audioLayer: String)
    }

    fun interface GetRankedRtcRegionsCallback {
        fun onRankedRtcRegions(regions: Array<String>)
    }

    fun interface GetSupportedVideoCodecsCallback {
        fun onSupportedVideoCodecs(codecs: Array<String>)
    }

    fun interface GetVideoInputDevicesCallback {
        fun onDevices(devices: Array<VideoInputDeviceDescription>)
    }

    fun interface LocalVoiceLevelChangedCallback {
        fun onLocalVoiceLevelChanged(level: Float, speaking: Int)
    }

    fun interface NoAudioInputCallback {
        fun onNoAudioInput(noAudioInput: Boolean)
    }

    fun interface OnVideoCallback {
        fun onVideo(userId: Long, ssrc: Int, streamId: String, streams: Array<StreamParameters>)
    }

    fun interface VideoFrameCallback {
        fun onFrame(frame: VideoFrame): Boolean
    }

    companion object {
        const val LOGLEVEL_DEBUG = 2
        const val LOGLEVEL_DEFAULT = -1

        private lateinit var krispVersion: String

        init {
            System.loadLibrary("discord")
        }
    }
}
