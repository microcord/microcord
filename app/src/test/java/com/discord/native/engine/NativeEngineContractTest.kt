package com.discord.native.engine

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeEngineContractTest {
    @Test
    fun jniSurfaceMatchesReference() {
        val engineCallbacks = mapOf(
            "ConnectToServerCallback" to "onConnectToServer(Lcom/discord/native/engine/ConnectionInfo;Ljava/lang/String;)V",
            "DeviceChangeCallback" to "onChange([Lcom/discord/native/engine/AudioInputDeviceDescription;[Lcom/discord/native/engine/AudioOutputDeviceDescription;[Lcom/discord/native/engine/VideoInputDeviceDescription;)V",
            "GetAudioInputDevicesCallback" to "onDevices([Lcom/discord/native/engine/AudioInputDeviceDescription;)V",
            "GetAudioOutputDevicesCallback" to "onDevices([Lcom/discord/native/engine/AudioOutputDeviceDescription;)V",
            "GetAudioSubsystemCallback" to "onAudioSubsystem(Ljava/lang/String;Ljava/lang/String;)V",
            "GetRankedRtcRegionsCallback" to "onRankedRtcRegions([Ljava/lang/String;)V",
            "GetSupportedVideoCodecsCallback" to "onSupportedVideoCodecs([Ljava/lang/String;)V",
            "GetVideoInputDevicesCallback" to "onDevices([Lcom/discord/native/engine/VideoInputDeviceDescription;)V",
            "OnNoInputCallback" to "onNoInput(Z)V",
            "OnVoiceCallback" to "onVoice(FI)V",
            "VideoFrameCallback" to "onFrame(Lorg/webrtc/VideoFrame;)Z",
        )
        val connectionCallbacks = mapOf(
            "GetEncryptionModesCallback" to "onEncryptionModes([Ljava/lang/String;)V",
            "GetStatsCallback" to "onStats(Ljava/lang/String;)V",
            "OnPingCallback" to "onPing(ILjava/lang/String;II)V",
            "OnPingTimeoutCallback" to "onPingTimeout(Ljava/lang/String;III)V",
            "OnSpeakingCallback" to "onSpeaking(Ljava/lang/String;I)V",
            "OnVideoCallback" to "onVideo(Ljava/lang/String;JLjava/lang/String;Ljava/lang/String;)V",
        )
        assertCallbacks(NativeEngine::class.java, engineCallbacks)
        assertCallbacks(NativeConnection::class.java, connectionCallbacks)

        assertNativeMethods(
            NativeEngine::class.java,
            setOf(
                "createVoiceConnection(JLjava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Lcom/discord/native/engine/NativeEngine\$ConnectToServerCallback;)Lcom/discord/native/engine/NativeConnection;",
                "enableBuiltInAEC(Z)V",
                "getAudioSubsystem(Lcom/discord/native/engine/NativeEngine\$GetAudioSubsystemCallback;)V",
                "getInputDevices(Lcom/discord/native/engine/NativeEngine\$GetAudioInputDevicesCallback;)V",
                "getOutputDevices(Lcom/discord/native/engine/NativeEngine\$GetAudioOutputDevicesCallback;)V",
                "getSupportedVideoCodecs(Lcom/discord/native/engine/NativeEngine\$GetSupportedVideoCodecsCallback;)V",
                "getVideoInputDevices(Lcom/discord/native/engine/NativeEngine\$GetVideoInputDevicesCallback;)V",
                "nativeCreateInstance(Landroid/content/Context;Lorg/webrtc/EglBase\$Context;I)J",
                "nativeDestroyInstance()V",
                "rankRtcRegions(Ljava/lang/String;Lcom/discord/native/engine/NativeEngine\$GetRankedRtcRegionsCallback;)V",
                "setAudioInputEnabled(Z)V",
                "setEmitVADLevel(ZZLjava/lang/String;)V",
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
                "setTransportOptions(Ljava/lang/String;)V",
                "setVideoInputDevice(Ljava/lang/String;)V",
                "setVideoInputDeviceIndex(I)V",
                "setVideoOutputSink(Ljava/lang/String;Lcom/discord/native/engine/NativeEngine\$VideoFrameCallback;)V",
            ),
        )
        assertNativeMethods(
            NativeConnection::class.java,
            setOf(
                "configureConnectionRetries(III)V",
                "destroyUser(Ljava/lang/String;)V",
                "getEncryptionModes(Lcom/discord/native/engine/NativeConnection\$GetEncryptionModesCallback;)V",
                "getFilteredStats(ILcom/discord/native/engine/NativeConnection\$GetStatsCallback;)V",
                "getStats(Lcom/discord/native/engine/NativeConnection\$GetStatsCallback;)V",
                "mergeUsers(Ljava/lang/String;)V",
                "nativeDestroyInstance()V",
                "setDesktopSource(Ljava/lang/String;ZLjava/lang/String;)V",
                "setLocalMute(Ljava/lang/String;Z)V",
                "setLocalPan(Ljava/lang/String;FF)V",
                "setLocalVolume(Ljava/lang/String;F)V",
                "setMinimumOutputDelay(I)V",
                "setNoInputThreshold(F)V",
                "setOnPingCallback(Lcom/discord/native/engine/NativeConnection\$OnPingCallback;)V",
                "setOnPingTimeoutCallback(Lcom/discord/native/engine/NativeConnection\$OnPingTimeoutCallback;)V",
                "setOnSpeakingCallback(Lcom/discord/native/engine/NativeConnection\$OnSpeakingCallback;)V",
                "setOnVideoCallback(Lcom/discord/native/engine/NativeConnection\$OnVideoCallback;)V",
                "setPTTActive(ZZ)V",
                "setPingInterval(I)V",
                "setSelfDeafen(Z)V",
                "setSelfMute(Z)V",
                "setTransportOptions(Ljava/lang/String;)V",
                "setVideoBroadcast(Z)V",
                "startBroadcast(Lorg/webrtc/VideoCapturer;J)V",
                "stopBroadcast()V",
            ),
        )
        assertFields(
            NativeEngine::class.java,
            mapOf(
                "Companion" to "Lcom/discord/native/engine/NativeEngine\$Companion;",
                "LOGLEVEL_DEBUG" to "I",
                "LOGLEVEL_DEFAULT" to "I",
                "logLevel" to "I",
                "nativeInstance" to "J",
            ),
        )
        assertFields(NativeConnection::class.java, mapOf("nativeInstance" to "J"))
        assertFields(AudioInputDeviceDescription::class.java, mapOf("name" to "Ljava/lang/String;", "guid" to "Ljava/lang/String;"))
        assertFields(AudioOutputDeviceDescription::class.java, mapOf("name" to "Ljava/lang/String;", "guid" to "Ljava/lang/String;"))
        assertFields(
            VideoInputDeviceDescription::class.java,
            mapOf(
                "name" to "Ljava/lang/String;",
                "guid" to "Ljava/lang/String;",
                "facing" to "Lcom/discord/native/engine/VideoInputDeviceFacing;",
            ),
        )
        assertFields(
            ConnectionInfo::class.java,
            mapOf(
                "isConnected" to "Z",
                "protocol" to "Ljava/lang/String;",
                "localAddress" to "Ljava/lang/String;",
                "localPort" to "I",
            ),
        )
        assertConstructors(NativeEngine::class.java, setOf("(Landroid/content/Context;I)V"))
        assertConstructors(NativeConnection::class.java, setOf("()V"))
        assertConstructors(AudioInputDeviceDescription::class.java, setOf("(Ljava/lang/String;Ljava/lang/String;)V"))
        assertConstructors(AudioOutputDeviceDescription::class.java, setOf("(Ljava/lang/String;Ljava/lang/String;)V"))
        assertConstructors(
            VideoInputDeviceDescription::class.java,
            setOf("(Ljava/lang/String;Ljava/lang/String;Lcom/discord/native/engine/VideoInputDeviceFacing;)V"),
        )
        assertConstructors(ConnectionInfo::class.java, setOf("(ZLjava/lang/String;Ljava/lang/String;I)V"))
        assertTrue(NativeEngine::class.java.declaredMethods.filter { it.name.startsWith("native") }.all { Modifier.isNative(it.modifiers) })
        assertEquals(listOf("Unknown", "Front", "Back"), VideoInputDeviceFacing.entries.map { it.name })
        assertEquals(ConnectionInfo(true, "udp", "127.0.0.1", 5000), ConnectionInfo(true, "udp", "127.0.0.1", 5000))
    }

    private fun assertCallbacks(owner: Class<*>, expected: Map<String, String>) {
        val callbacks = owner.declaredClasses.associateBy { it.simpleName }
        assertEquals(expected.keys, callbacks.keys - "Companion")
        expected.forEach { (name, descriptor) ->
            assertEquals(setOf(descriptor), callbacks.getValue(name).declaredMethods.mapTo(mutableSetOf()) { it.descriptor() })
        }
    }

    private fun assertNativeMethods(owner: Class<*>, expected: Set<String>) {
        val methods = owner.declaredMethods.filter { Modifier.isNative(it.modifiers) }
        assertEquals(expected, methods.mapTo(mutableSetOf()) { it.descriptor() })
        assertTrue(methods.all { Modifier.isNative(it.modifiers) })
    }

    private fun assertFields(owner: Class<*>, expected: Map<String, String>) =
        assertEquals(expected, owner.declaredFields.filterNot { it.isSynthetic }.associate { it.name to it.type.descriptor() })

    private fun assertConstructors(owner: Class<*>, expected: Set<String>) =
        assertEquals(
            expected,
            owner.declaredConstructors.mapTo(mutableSetOf()) {
                it.parameterTypes.joinToString("", "(", ")V") { type -> type.descriptor() }
            },
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
