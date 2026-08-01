package com.hammerandchisel.libdiscord

import android.content.Context
import co.discord.media_engine.AudioInputDeviceDescription
import co.discord.media_engine.AudioOutputDeviceDescription
import co.discord.media_engine.Connection
import co.discord.media_engine.MediaType
import co.discord.media_engine.RtcRegion
import co.discord.media_engine.StreamParameters
import co.discord.media_engine.VideoInputDeviceDescription
import co.discord.media_engine.VideoInputDeviceFacing
import com.discord.R
import com.discord.app.AppLog
import com.discord.native.engine.NativeEngine
import com.google.gson.Gson
import java.util.Collections
import org.webrtc.VideoFrame

private val gson = Gson()

private data class EngineTransportOptions(
    val automaticGainControl: Boolean? = null,
    val bypassSystemProcessing: Boolean? = null,
    val captureVideoFrameRate: Int? = null,
    val ducking: Boolean? = null,
    val echoCancellation: Boolean? = null,
    val idleJitterBufferFlush: Boolean? = null,
    val noiseCancellation: Boolean? = null,
    val noiseSuppression: Boolean? = null,
)

@Suppress("unused")
class Discord @JvmOverloads constructor(
    private val context: Context,
    logLevel: Int = LOGLEVEL_DEFAULT,
) {
    private val nativeEngine: NativeEngine
    private var localVoiceLevelChangedCallback: LocalVoiceLevelChangedCallback? = null

    @Volatile
    private var disposed = false

    data class ConnectionInfo(
        @JvmField val isConnected: Boolean,
        @JvmField val protocol: String,
        @JvmField val localAddress: String,
        @JvmField val localPort: Int,
    )

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

    data class CodecCapability(
        val codec: String,
        val decode: Boolean,
        val encode: Boolean,
    )

    private data class ConnectionOptions(
        val context: String,
        val address: String,
        val port: Int,
        val ssrc: Int,
        val experiments: List<String>,
        val modes: List<String>,
        val streamParameters: List<NativeStreamParameters>,
        val qosEnabled: Boolean,
    )

    data class NativeStreamParameters(
        val type: String,
        val rid: String,
        val ssrc: Int,
        val maxBitrate: Int,
        val soundshare: Boolean? = null,
        val quality: Int? = null,
        val maxResolution: MaxResolution? = null,
        val maxPixelCount: Int? = null,
        val rtxSsrc: Int? = null,
        val maxFrameRate: Int? = null,
        val active: Boolean? = null,
    ) {
        data class MaxResolution(
            val type: String,
            val width: Int,
            val height: Int,
        )

        companion object {
            fun from(parameters: StreamParameters) = NativeStreamParameters(
                type = if (parameters.type == MediaType.Video) "video" else "audio",
                rid = parameters.rid,
                ssrc = parameters.ssrc,
                maxBitrate = parameters.maxBitrate,
                quality = parameters.quality,
                maxResolution = MaxResolution("fixed", 1280, 720),
                rtxSsrc = parameters.rtxSsrc,
                maxFrameRate = 30,
                active = parameters.active,
            )
        }
    }

    init {
        krispVersion = context.getString(R.string.krisp_model_version)
        nativeEngine = NativeEngine(context, logLevel)
        setTransportOptions(
            EngineTransportOptions(
                bypassSystemProcessing = true,
                ducking = false,
                idleJitterBufferFlush = true,
            ),
        )
        nativeEngine.setSidechainCompression(true)
        nativeEngine.setInputDevice("default")
        nativeEngine.setOutputDevice("default")
        nativeEngine.setInputVolume(1f)
        nativeEngine.setOnVoiceCallback { level, speaking ->
            localVoiceLevelChangedCallback?.onLocalVoiceLevelChanged(level, speaking)
        }
        nativeEngine.setAudioInputInitializationCallback { info ->
            AppLog.d(TAG, "Audio input initialized in ${info.timeToInitializedNanos}ns")
        }
    }

    fun connectToServer(
        ssrc: Int,
        userId: Long,
        ip: String,
        port: Int,
        streams: Array<StreamParameters>,
        callback: ConnectToServerCallback,
    ): Connection {
        check(!disposed) { "Discord engine is disposed" }
        val nativeContext = if (nextConnectionIsStream) getStreamContext(ssrc) else DEFAULT_CONTEXT
        nextConnectionIsStream = false
        val videoParameters = streams
            .map(NativeStreamParameters::from)
            .map { parameters ->
                val lowQuality = (parameters.quality ?: 100) < 100
                parameters.copy(
                    active = true,
                    maxBitrate = when {
                        parameters.maxBitrate > 0 -> parameters.maxBitrate
                        lowQuality -> DEFAULT_VIDEO_MAX_BITRATE / 3
                        else -> DEFAULT_VIDEO_MAX_BITRATE
                    },
                    maxResolution = if (lowQuality) NativeStreamParameters.MaxResolution("fixed", 640, 360)
                    else parameters.maxResolution,
                    maxPixelCount = if (lowQuality) 640 * 360 else parameters.maxPixelCount,
                    maxFrameRate = if (lowQuality) 20 else parameters.maxFrameRate,
                )
            }
            .sortedByDescending { it.quality ?: 100 }
        val streamParameters = listOf(
            NativeStreamParameters(
                type = "audio",
                rid = "",
                ssrc = ssrc,
                maxBitrate = 64_000,
                soundshare = false,
            ),
        ) + videoParameters
        val nativeConnection = nativeEngine.createVoiceConnection(
            userId.toString(),
            gson.toJson(
                ConnectionOptions(
                    context = nativeContext,
                    address = ip,
                    port = port,
                    ssrc = ssrc,
                    experiments = emptyList(),
                    modes = listOf("aead_aes256_gcm_rtpsize", "aead_xchacha20_poly1305_rtpsize"),
                    streamParameters = streamParameters,
                    qosEnabled = false,
                ),
            ),
        ) { info, error ->
            callback.onConnectToServer(
                ConnectionInfo(info.isConnected, info.protocol, info.localAddress, info.localPort),
                error,
            )
        }
        return Connection(
            nativeConnection,
            videoParameters,
            nativeEngine,
            nativeContext.takeIf(::isStreamContext),
        )
    }

    fun crash() {
        AppLog.w(TAG, "Native crash request is unavailable on the modern engine")
    }

    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        localVoiceLevelChangedCallback = null
        nativeEngine.dispose()
    }

    @Deprecated("Use the callback overload")
    fun enableBuiltInAEC(enabled: Boolean) = nativeEngine.enableBuiltInAEC(enabled)

    fun enableBuiltInAEC(enabled: Boolean, callback: BuiltinAECCallback?) {
        if (disposed) return
        nativeEngine.enableBuiltInAEC(enabled)
        callback?.onConfigureBuiltinAEC(enabled, true, enabled)
    }

    fun getAudioInputDevices(callback: GetAudioInputDevicesCallback) {
        if (disposed) return
        nativeEngine.getInputDevices { devices ->
            callback.onDevices(devices.filterNotNull().map { AudioInputDeviceDescription(it.name, it.guid) }.toTypedArray())
        }
    }

    fun getAudioOutputDevices(callback: GetAudioOutputDevicesCallback) {
        if (disposed) return
        nativeEngine.getOutputDevices { devices ->
            callback.onDevices(devices.filterNotNull().map { AudioOutputDeviceDescription(it.name, it.guid) }.toTypedArray())
        }
    }

    fun getAudioSubsystem(callback: GetAudioSubsystemCallback) {
        if (disposed) return
        nativeEngine.getAudioSubsystem(callback::onAudioSubsystem)
    }

    fun getRankedRtcRegions(regions: Array<RtcRegion>, callback: GetRankedRtcRegionsCallback) {
        if (disposed) return
        nativeEngine.rankRtcRegions(gson.toJson(regions), callback::onRankedRtcRegions)
    }

    fun getSupportedVideoCodecs(callback: GetSupportedVideoCodecsCallback) {
        if (disposed) return
        nativeEngine.getCodecCapabilities { json ->
            val capabilities = runCatching {
                gson.fromJson(json, Array<CodecCapability>::class.java).orEmpty()
            }.getOrElse {
                AppLog.e(TAG, "Unable to parse native codec capabilities")
                emptyArray()
            }
            codecCapabilities = capabilities.associateBy(CodecCapability::codec)
            callback.onSupportedVideoCodecs(capabilities.filter(CodecCapability::decode).map(CodecCapability::codec).toTypedArray())
        }
    }

    fun getVideoInputDevices(callback: GetVideoInputDevicesCallback) {
        if (disposed) return
        nativeEngine.getVideoInputDevices { devices ->
            callback.onDevices(
                devices.filterNotNull().map { device ->
                    VideoInputDeviceDescription(
                        device.name,
                        device.guid,
                        when (device.facing) {
                            com.discord.native.engine.VideoInputDeviceFacing.Back -> VideoInputDeviceFacing.Back
                            com.discord.native.engine.VideoInputDeviceFacing.Front -> VideoInputDeviceFacing.Front
                            com.discord.native.engine.VideoInputDeviceFacing.Unknown -> VideoInputDeviceFacing.Unknown
                        },
                    )
                }.toTypedArray(),
            )
        }
    }

    fun setAudioInputEnabled(enabled: Boolean) {
        if (!disposed) nativeEngine.setAudioInputEnabled(enabled)
    }

    fun setAutomaticGainControl(enabled: Boolean) =
        setTransportOptions(EngineTransportOptions(automaticGainControl = enabled))

    @Deprecated("Use the callback overload")
    fun setEchoCancellation(enabled: Boolean) = setEchoCancellation(enabled, false, null)

    fun setEchoCancellation(enabled: Boolean, mobileMode: Boolean, callback: AecConfigCallback?) {
        setTransportOptions(EngineTransportOptions(echoCancellation = enabled))
        callback?.onConfigureAEC(true, mobileMode, enabled, !enabled, true)
    }

    fun setKeepAliveChannel(enabled: Boolean) = Unit

    fun setMicVolume(volume: Float) {
        if (!disposed) nativeEngine.setInputVolume(volume)
    }

    fun setNoAudioInputCallback(callback: NoAudioInputCallback?) {
        if (!disposed) nativeEngine.setOnNoInputCallback { callback?.onNoAudioInput(it) }
    }

    fun setNoAudioInputThreshold(threshold: Float) {
        if (!disposed) nativeEngine.setNoInputThreshold(threshold)
    }

    fun setNoiseCancellation(enabled: Boolean) =
        setTransportOptions(EngineTransportOptions(noiseCancellation = enabled))

    fun setNoiseSuppression(enabled: Boolean) =
        setTransportOptions(EngineTransportOptions(noiseSuppression = enabled))

    fun setPlayoutDevice(index: Int) {
        if (!disposed) nativeEngine.setOutputDeviceIndex(index)
    }

    fun setRecordingDevice(index: Int) {
        if (!disposed) nativeEngine.setInputDeviceIndex(index)
    }

    fun setSpeakerVolume(volume: Float) {
        if (!disposed) nativeEngine.setOutputVolume(volume)
    }

    fun setVideoInputDevice(index: Int) {
        if (!disposed) nativeEngine.setVideoInputDeviceIndex(index)
    }

    fun setVideoInputDevice(guid: String) {
        if (!disposed) nativeEngine.setVideoInputDevice(guid)
    }

    fun setCaptureVideoFrameRate(fps: Int) =
        setTransportOptions(EngineTransportOptions(captureVideoFrameRate = fps))

    fun setVideoOutputSink(streamId: String, callback: VideoFrameCallback?) {
        if (disposed) return
        nativeEngine.setVideoOutputSink(streamId, callback?.let { sink ->
            NativeEngine.VideoFrameCallback { frame, _ -> sink.onFrame(frame) }
        })
    }

    fun signalVideoOutputSinkReady(streamId: String) = Unit

    fun setLocalVoiceLevelChangedCallback(callback: LocalVoiceLevelChangedCallback?) {
        if (disposed) return
        nativeEngine.setEmitVADLevel2(callback != null)
        localVoiceLevelChangedCallback = callback
    }

    private fun setTransportOptions(options: EngineTransportOptions) {
        if (!disposed) nativeEngine.setTransportOptions(gson.toJson(options))
    }

    companion object {
        const val LOGLEVEL_DEBUG = NativeEngine.LOGLEVEL_DEBUG
        const val LOGLEVEL_DEFAULT = NativeEngine.LOGLEVEL_DEFAULT
        const val DEFAULT_VIDEO_MAX_BITRATE = 2_500_000
        private const val DEFAULT_CONTEXT = "stream"
        private const val STREAM_CONTEXT = "stream"
        private const val TAG = "Discord"

        @JvmStatic
        @Volatile
        var nextConnectionIsStream = false

        private val streamContexts = Collections.synchronizedSet(HashSet<String>())

        internal fun getStreamContext(ssrc: Int): String = synchronized(streamContexts) {
            val name = if (STREAM_CONTEXT in streamContexts) "$STREAM_CONTEXT-$ssrc" else STREAM_CONTEXT
            streamContexts += name
            name
        }

        @JvmStatic
        fun releaseStreamContext(context: String?) {
            if (context != null) streamContexts -= context
        }

        internal fun isStreamContext(context: String) = context.startsWith(STREAM_CONTEXT)

        @JvmStatic
        @Volatile
        var codecCapabilities: Map<String, CodecCapability> = emptyMap()
            private set

        @JvmStatic
        private var krispVersion = ""
    }
}
