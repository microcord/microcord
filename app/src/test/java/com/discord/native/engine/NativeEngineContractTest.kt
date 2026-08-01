package com.discord.native.engine

import com.google.gson.Gson
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeEngineContractTest {
    @Test
    fun jniSurfaceMatchesDiscord333() {
        assertCallbacks(
            NativeEngine::class.java,
            mapOf(
                "AudioInputInitializationCallback" to "onAudioInputInitialized(Lcom/discord/native/engine/AudioInputInitializationInfo;)V",
                "ConnectToServerCallback" to "onConnectToServer(Lcom/discord/native/engine/ConnectionInfo;Ljava/lang/String;)V",
                "DeviceChangeCallback" to "onChange([Lcom/discord/native/engine/AudioInputDeviceDescription;[Lcom/discord/native/engine/AudioOutputDeviceDescription;[Lcom/discord/native/engine/VideoInputDeviceDescription;)V",
                "GetAudioInputDevicesCallback" to "onDevices([Lcom/discord/native/engine/AudioInputDeviceDescription;)V",
                "GetAudioOutputDevicesCallback" to "onDevices([Lcom/discord/native/engine/AudioOutputDeviceDescription;)V",
                "GetAudioSubsystemCallback" to "onAudioSubsystem(Ljava/lang/String;Ljava/lang/String;)V",
                "GetCodecCapabilitiesCallback" to "onCodecCapabilities(Ljava/lang/String;)V",
                "GetCodecSurveyCallback" to "onCodecSurvey(Ljava/lang/String;)V",
                "GetRankedRtcRegionsCallback" to "onRankedRtcRegions([Ljava/lang/String;)V",
                "GetVideoInputDevicesCallback" to "onDevices([Lcom/discord/native/engine/VideoInputDeviceDescription;)V",
                "MLSSigningKeyCallback" to "onMLSSigningKey(Ljava/lang/String;Ljava/lang/String;)V",
                "OnNoInputCallback" to "onNoInput(Z)V",
                "OnVoiceCallback" to "onVoice(FI)V",
                "StartLocalAudioRecordingCallback" to "onStartLocalAudioRecording(Z)V",
                "StopLocalAudioRecordingCallback" to "onStopLocalAudioRecording(Ljava/lang/String;I)V",
                "VideoFrameCallback" to "onFrame(Lorg/webrtc/VideoFrame;Z)Z",
                "VoiceProcessingErrorCallback" to "onVoiceProcessingError(I)V",
            ),
        )
        assertCallbacks(
            NativeConnection::class.java,
            mapOf(
                "GetEncryptionModesCallback" to "onEncryptionModes([Ljava/lang/String;)V",
                "GetStatsCallback" to "onStats(Ljava/lang/String;)V",
                "MLSCommitTransitionCallback" to "onMLSProcessedCommit(ZILjava/lang/String;)V",
                "MLSFailureCallback" to "onMLSFailureCallback(Ljava/lang/String;Ljava/lang/String;)V",
                "MLSKeyPackageCallback" to "onMLSKeyPackage(Ljava/lang/String;)V",
                "MLSPairwiseFingerprintCallback" to "onMLSPairwiseFingerprint(Ljava/lang/String;)V",
                "MLSProcessProposalsCallback" to "onMLSCommitWelcome(Ljava/lang/String;)V",
                "MLSWelcomeCallback" to "onMLSProcessedWelcome(ZILjava/lang/String;)V",
                "OnFirstFrameCallback" to "onFirstFrame(Ljava/lang/String;JLjava/lang/String;)V",
                "OnPingCallback" to "onPing(ILjava/lang/String;II)V",
                "OnPingTimeoutCallback" to "onPingTimeout(Ljava/lang/String;III)V",
                "OnSpeakingCallback" to "onSpeaking(Ljava/lang/String;IF)V",
                "OnSpeakingWhileMutedCallback" to "onSpeakingWhileMuted()V",
                "OnVideoCallback" to "onVideo(Ljava/lang/String;JLjava/lang/String;Ljava/lang/String;)V",
                "SecureFramesStateUpdateCallback" to "onSecureFramesStateUpdateCallback(Ljava/lang/String;)V",
                "SecureFramesTransitionReadyCallback" to "onTransitionReady()V",
            ),
        )

        assertNativeMethods(
            NativeEngine::class.java,
            setOf(
                "createVoiceConnection(Ljava/lang/String;Ljava/lang/String;Lcom/discord/native/engine/NativeEngine\$ConnectToServerCallback;)Lcom/discord/native/engine/NativeConnection;",
                "enableBuiltInAEC(Z)V",
                "getAudioSubsystem(Lcom/discord/native/engine/NativeEngine\$GetAudioSubsystemCallback;)V",
                "getCodecCapabilities(Lcom/discord/native/engine/NativeEngine\$GetCodecCapabilitiesCallback;)V",
                "getCodecSurvey(Lcom/discord/native/engine/NativeEngine\$GetCodecSurveyCallback;)V",
                "getInputDevices(Lcom/discord/native/engine/NativeEngine\$GetAudioInputDevicesCallback;)V",
                "getMLSSigningKeyB64(Ljava/lang/String;ILcom/discord/native/engine/NativeEngine\$MLSSigningKeyCallback;)V",
                "getMaxSupportedProtocolVersion()I",
                "getOutputDevices(Lcom/discord/native/engine/NativeEngine\$GetAudioOutputDevicesCallback;)V",
                "getVideoInputDevices(Lcom/discord/native/engine/NativeEngine\$GetVideoInputDevicesCallback;)V",
                "nativeCreateInstance(Landroid/content/Context;Lorg/webrtc/EglBase\$Context;IZ)J",
                "nativeDestroyInstance()V",
                "rankRtcRegions(Ljava/lang/String;Lcom/discord/native/engine/NativeEngine\$GetRankedRtcRegionsCallback;)V",
                "setAudioInputEnabled(Z)V",
                "setAudioInputInitializationCallback(Lcom/discord/native/engine/NativeEngine\$AudioInputInitializationCallback;)V",
                "setEmitVADLevel2(Z)V",
                "setHasFullbandPerformance(Z)V",
                "setInputDevice(Ljava/lang/String;)V",
                "setInputDeviceIndex(I)V",
                "setInputVolume(F)V",
                "setNoInputThreshold(F)V",
                "setOnDeviceChangeCallback(Lcom/discord/native/engine/NativeEngine\$DeviceChangeCallback;)V",
                "setOnNoInputCallback(Lcom/discord/native/engine/NativeEngine\$OnNoInputCallback;)V",
                "setOnVoiceCallback(Lcom/discord/native/engine/NativeEngine\$OnVoiceCallback;)V",
                "setOutputDevice(Ljava/lang/String;)V",
                "setOutputDeviceIndex(I)V",
                "setOutputVolume(F)V",
                "setSidechainCompression(Z)V",
                "setTransportOptions(Ljava/lang/String;)V",
                "setVideoInputDevice(Ljava/lang/String;)V",
                "setVideoInputDeviceIndex(I)V",
                "setVideoOutputSink(Ljava/lang/String;Lcom/discord/native/engine/NativeEngine\$VideoFrameCallback;)V",
                "setVoiceProcessingErrorCallback(Lcom/discord/native/engine/NativeEngine\$VoiceProcessingErrorCallback;)V",
                "startLocalAudioRecording(Ljava/lang/String;Lcom/discord/native/engine/NativeEngine\$StartLocalAudioRecordingCallback;)V",
                "stopLocalAudioRecording(Lcom/discord/native/engine/NativeEngine\$StopLocalAudioRecordingCallback;)V",
                "updateFieldTrial(Ljava/lang/String;Ljava/lang/String;)V",
            ),
        )
        assertNativeMethods(
            NativeConnection::class.java,
            setOf(
                "configureConnectionRetries(III)V", "destroyUser(Ljava/lang/String;)V",
                "executeSecureFramesTransition(I)V", "fastUdpReconnect()V",
                "getEncryptionModes(Lcom/discord/native/engine/NativeConnection\$GetEncryptionModesCallback;)V",
                "getFilteredStats(ILcom/discord/native/engine/NativeConnection\$GetStatsCallback;)V",
                "getMLSKeyPackageB64(Lcom/discord/native/engine/NativeConnection\$MLSKeyPackageCallback;)V",
                "getMLSPairwiseFingerprintB64(ILjava/lang/String;Lcom/discord/native/engine/NativeConnection\$MLSPairwiseFingerprintCallback;)V",
                "getStats(Lcom/discord/native/engine/NativeConnection\$GetStatsCallback;)V",
                "mergeUsers(Ljava/lang/String;)V", "nativeDestroyInstance()V",
                "prepareMLSCommitTransitionB64(ILjava/lang/String;Lcom/discord/native/engine/NativeConnection\$MLSCommitTransitionCallback;)V",
                "prepareSecureFramesEpoch(Ljava/lang/String;ILjava/lang/String;)V",
                "prepareSecureFramesTransition(IILcom/discord/native/engine/NativeConnection\$SecureFramesTransitionReadyCallback;)V",
                "processMLSProposalsB64(Ljava/lang/String;Lcom/discord/native/engine/NativeConnection\$MLSProcessProposalsCallback;)V",
                "processMLSWelcomeB64(ILjava/lang/String;Lcom/discord/native/engine/NativeConnection\$MLSWelcomeCallback;)V",
                "setDesktopSource(Ljava/lang/String;ZLjava/lang/String;)V", "setLocalMute(Ljava/lang/String;Z)V",
                "setLocalPan(Ljava/lang/String;FF)V", "setLocalVolume(Ljava/lang/String;F)V",
                "setMinimumOutputDelay(I)V", "setNoInputThreshold(F)V",
                "setOnFirstFrameCallback(Lcom/discord/native/engine/NativeConnection\$OnFirstFrameCallback;)V",
                "setOnMLSFailureCallback(Lcom/discord/native/engine/NativeConnection\$MLSFailureCallback;)V",
                "setOnPingCallback(Lcom/discord/native/engine/NativeConnection\$OnPingCallback;)V",
                "setOnPingTimeoutCallback(Lcom/discord/native/engine/NativeConnection\$OnPingTimeoutCallback;)V",
                "setOnSpeakingCallback(Lcom/discord/native/engine/NativeConnection\$OnSpeakingCallback;)V",
                "setOnSpeakingWhileMutedCallback(Lcom/discord/native/engine/NativeConnection\$OnSpeakingWhileMutedCallback;)V",
                "setOnVideoCallback(Lcom/discord/native/engine/NativeConnection\$OnVideoCallback;)V",
                "setPTTActive(ZZZ)V", "setPingInterval(I)V",
                "setSecureFramesStateUpdateCallback(Lcom/discord/native/engine/NativeConnection\$SecureFramesStateUpdateCallback;)V",
                "setSelfDeafen(Z)V", "setSelfMute(Z)V", "setTransportOptions(Ljava/lang/String;)V",
                "setVideoBroadcast(Z)V", "startBroadcast(Lorg/webrtc/VideoCapturer;J)V", "stopBroadcast()V",
                "updateMLSExternalSenderB64(Ljava/lang/String;)V",
            ),
        )
        assertFalse(Modifier.isNative(NativeEngine::class.java.getDeclaredMethod("dispose").modifiers))
        assertFalse(Modifier.isNative(NativeConnection::class.java.getDeclaredMethod("dispose").modifiers))
    }

    @Test
    fun modelsPreserveModernFieldsAndJsonNames() {
        val info = ConnectionInfo(true, "udp", "127.0.0.1", 5000, 12, 34)
        assertEquals(info, Gson().fromJson(Gson().toJson(info), ConnectionInfo::class.java))
        assertEquals(12, info.createConnectionTime)
        assertEquals(34, info.connectTime)
        assertEquals(listOf("Unknown", "Front", "Back"), VideoInputDeviceFacing.entries.map { it.name })
        assertTrue(NativeConnection::class.java.getDeclaredField("disposed").type == Boolean::class.javaPrimitiveType)
    }

    private fun assertCallbacks(owner: Class<*>, expected: Map<String, String>) {
        val callbacks = owner.declaredClasses.associateBy { it.simpleName }
        assertEquals(expected.keys, callbacks.keys - "Companion")
        expected.forEach { (name, descriptor) ->
            assertEquals(setOf(descriptor), callbacks.getValue(name).declaredMethods.mapTo(mutableSetOf()) { it.descriptor() })
        }
    }

    private fun assertNativeMethods(owner: Class<*>, expected: Set<String>) = assertEquals(
        expected,
        owner.declaredMethods.filter { Modifier.isNative(it.modifiers) }.mapTo(mutableSetOf()) { it.descriptor() },
    )

    private fun Method.descriptor() = name + parameterTypes.joinToString("", "(", ")") { it.descriptor() } + returnType.descriptor()

    private fun Class<*>.descriptor(): String = when {
        isPrimitive -> when (this) {
            Void.TYPE -> "V"
            java.lang.Boolean.TYPE -> "Z"
            java.lang.Integer.TYPE -> "I"
            java.lang.Long.TYPE -> "J"
            java.lang.Float.TYPE -> "F"
            else -> error("Unexpected primitive $this")
        }
        isArray -> name.replace('.', '/')
        else -> "L${name.replace('.', '/')};"
    }
}
