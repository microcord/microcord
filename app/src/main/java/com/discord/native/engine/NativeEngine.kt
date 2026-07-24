package com.discord.native.engine

import android.content.Context
import co.discord.media_engine.CameraEnumeratorProvider
import co.discord.media_engine.SharedEglBaseContext
import org.webrtc.EglBase
import org.webrtc.VideoFrame

class NativeEngine(
    context: Context,
    private val logLevel: Int,
) {
    private val nativeInstance: Long

    init {
        CameraEnumeratorProvider.maybeInit(context.applicationContext)
        nativeInstance = nativeCreateInstance(context.applicationContext, SharedEglBaseContext.getEglContext(), logLevel)
    }

    private external fun nativeCreateInstance(context: Context, eglContext: EglBase.Context, logLevel: Int): Long
    private external fun nativeDestroyInstance()

    fun dispose() = nativeDestroyInstance()

    external fun setTransportOptions(optionsJSON: String)
    external fun setInputVolume(volume: Float)
    external fun setOutputVolume(volume: Float)
    external fun setEmitVADLevel(enable: Boolean, playback: Boolean, optionsJSON: String)
    external fun getInputDevices(callback: GetAudioInputDevicesCallback)
    external fun setInputDeviceIndex(deviceIndex: Int)
    external fun setInputDevice(deviceIndex: String)
    external fun getOutputDevices(callback: GetAudioOutputDevicesCallback)
    external fun setOutputDeviceIndex(deviceIndex: Int)
    external fun setOutputDevice(deviceIndex: String)
    external fun getVideoInputDevices(callback: GetVideoInputDevicesCallback)
    external fun setVideoInputDeviceIndex(deviceIndex: Int)
    external fun setVideoInputDevice(deviceIndex: String)
    external fun setOnDeviceChangeCallback(callback: DeviceChangeCallback)
    external fun setVideoOutputSink(streamIdentifier: String, callback: VideoFrameCallback?)
    external fun setNoInputThreshold(threshold: Float)
    external fun setOnNoInputCallback(callback: OnNoInputCallback)
    external fun setOnVoiceCallback(callback: OnVoiceCallback)
    external fun getAudioSubsystem(callback: GetAudioSubsystemCallback)
    external fun getSupportedVideoCodecs(callback: GetSupportedVideoCodecsCallback)
    external fun rankRtcRegions(regionsWithIpsJSON: String, callback: GetRankedRtcRegionsCallback)
    external fun enableBuiltInAEC(enable: Boolean)
    external fun setAudioInputEnabled(enable: Boolean)
    external fun createVoiceConnection(
        ssrc: Long,
        userId: String,
        serverIp: String,
        port: Int,
        experimentsJSON: String,
        streamParametersJSON: String,
        callback: ConnectToServerCallback,
    ): NativeConnection

    interface ConnectToServerCallback {
        fun onConnectToServer(info: ConnectionInfo, error: String)
    }

    interface DeviceChangeCallback {
        fun onChange(
            audioInputDevices: Array<AudioInputDeviceDescription>,
            audioOutputDevices: Array<AudioOutputDeviceDescription>,
            videoInputDevices: Array<VideoInputDeviceDescription>,
        )
    }

    interface GetAudioInputDevicesCallback {
        fun onDevices(devices: Array<AudioInputDeviceDescription>)
    }

    interface GetAudioOutputDevicesCallback {
        fun onDevices(devices: Array<AudioOutputDeviceDescription>)
    }

    interface GetAudioSubsystemCallback {
        fun onAudioSubsystem(subsystem: String, audioLayer: String)
    }

    interface GetRankedRtcRegionsCallback {
        fun onRankedRtcRegions(regions: Array<String>)
    }

    interface GetSupportedVideoCodecsCallback {
        fun onSupportedVideoCodecs(codecs: Array<String>)
    }

    interface GetVideoInputDevicesCallback {
        fun onDevices(devices: Array<VideoInputDeviceDescription>)
    }

    interface OnNoInputCallback {
        fun onNoInput(input: Boolean)
    }

    interface OnVoiceCallback {
        fun onVoice(level: Float, speaking: Int)
    }

    interface VideoFrameCallback {
        fun onFrame(frame: VideoFrame): Boolean
    }

    companion object {
        const val LOGLEVEL_DEBUG = 2
        const val LOGLEVEL_DEFAULT = -1

        init {
            System.loadLibrary("discord")
        }
    }
}
