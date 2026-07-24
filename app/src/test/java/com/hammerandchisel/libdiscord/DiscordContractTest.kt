package com.hammerandchisel.libdiscord

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordContractTest {
    @Test
    fun nestedTypesAndNativeDescriptorsMatchReference() {
        val discord = Class.forName(Discord::class.java.name, false, Discord::class.java.classLoader)
        assertEquals(
            setOf(
                "AecConfigCallback",
                "BuiltinAECCallback",
                "ConnectToServerCallback",
                "ConnectionInfo",
                "GetAudioInputDevicesCallback",
                "GetAudioOutputDevicesCallback",
                "GetAudioSubsystemCallback",
                "GetRankedRtcRegionsCallback",
                "GetSupportedVideoCodecsCallback",
                "GetVideoInputDevicesCallback",
                "LocalVoiceLevelChangedCallback",
                "NoAudioInputCallback",
                "OnVideoCallback",
                "VideoFrameCallback",
            ),
            discord.declaredClasses.mapTo(mutableSetOf()) { it.simpleName } - "Companion",
        )

        val callbacks = mapOf(
            "AecConfigCallback" to "onConfigureAEC(ZZZZZ)V",
            "BuiltinAECCallback" to "onConfigureBuiltinAEC(ZZZ)V",
            "ConnectToServerCallback" to "onConnectToServer(Lcom/hammerandchisel/libdiscord/Discord\$ConnectionInfo;Ljava/lang/String;)V",
            "GetAudioInputDevicesCallback" to "onDevices([Lco/discord/media_engine/AudioInputDeviceDescription;)V",
            "GetAudioOutputDevicesCallback" to "onDevices([Lco/discord/media_engine/AudioOutputDeviceDescription;)V",
            "GetAudioSubsystemCallback" to "onAudioSubsystem(Ljava/lang/String;Ljava/lang/String;)V",
            "GetRankedRtcRegionsCallback" to "onRankedRtcRegions([Ljava/lang/String;)V",
            "GetSupportedVideoCodecsCallback" to "onSupportedVideoCodecs([Ljava/lang/String;)V",
            "GetVideoInputDevicesCallback" to "onDevices([Lco/discord/media_engine/VideoInputDeviceDescription;)V",
            "LocalVoiceLevelChangedCallback" to "onLocalVoiceLevelChanged(FI)V",
            "NoAudioInputCallback" to "onNoAudioInput(Z)V",
            "OnVideoCallback" to "onVideo(JILjava/lang/String;[Lco/discord/media_engine/StreamParameters;)V",
            "VideoFrameCallback" to "onFrame(Lorg/webrtc/VideoFrame;)Z",
        )
        val nested = discord.declaredClasses.associateBy { it.simpleName }
        callbacks.forEach { (name, descriptor) ->
            assertEquals(setOf(descriptor), nested.getValue(name).declaredMethods.mapTo(mutableSetOf()) { it.descriptor() })
        }

        val connectionInfo = nested.getValue("ConnectionInfo")
        assertEquals(
            mapOf("isConnected" to "Z", "protocol" to "Ljava/lang/String;", "localAddress" to "Ljava/lang/String;", "localPort" to "I"),
            connectionInfo.declaredFields.associate { it.name to it.type.descriptor() },
        )
        assertEquals(
            setOf("(ZLjava/lang/String;Ljava/lang/String;I)V"),
            connectionInfo.declaredConstructors.mapTo(mutableSetOf()) {
                it.parameterTypes.joinToString("", "(", ")V") { type -> type.descriptor() }
            },
        )

        val expected = setOf(
            "connectToServer(IJLjava/lang/String;I[Lco/discord/media_engine/StreamParameters;Lcom/hammerandchisel/libdiscord/Discord\$ConnectToServerCallback;)Lco/discord/media_engine/Connection;",
            "crash()V",
            "dispose()V",
            "enableBuiltInAEC(Z)V",
            "enableBuiltInAEC(ZLcom/hammerandchisel/libdiscord/Discord\$BuiltinAECCallback;)V",
            "getAudioInputDevices(Lcom/hammerandchisel/libdiscord/Discord\$GetAudioInputDevicesCallback;)V",
            "getAudioOutputDevices(Lcom/hammerandchisel/libdiscord/Discord\$GetAudioOutputDevicesCallback;)V",
            "getAudioSubsystem(Lcom/hammerandchisel/libdiscord/Discord\$GetAudioSubsystemCallback;)V",
            "getRankedRtcRegions([Lco/discord/media_engine/RtcRegion;Lcom/hammerandchisel/libdiscord/Discord\$GetRankedRtcRegionsCallback;)V",
            "getSupportedVideoCodecs(Lcom/hammerandchisel/libdiscord/Discord\$GetSupportedVideoCodecsCallback;)V",
            "getVideoInputDevices(Lcom/hammerandchisel/libdiscord/Discord\$GetVideoInputDevicesCallback;)V",
            "nativeConstructor(Landroid/content/Context;Lorg/webrtc/EglBase\$Context;I)J",
            "setAudioInputEnabled(Z)V",
            "setAutomaticGainControl(Z)V",
            "setEchoCancellation(Z)V",
            "setEchoCancellation(ZZLcom/hammerandchisel/libdiscord/Discord\$AecConfigCallback;)V",
            "setKeepAliveChannel(Z)V",
            "setLocalVoiceLevelChangedCallbackNative(Z)V",
            "setMicVolume(F)V",
            "setNoAudioInputCallback(Lcom/hammerandchisel/libdiscord/Discord\$NoAudioInputCallback;)V",
            "setNoAudioInputThreshold(F)V",
            "setNoiseCancellation(Z)V",
            "setNoiseSuppression(Z)V",
            "setPlayoutDevice(I)V",
            "setRecordingDevice(I)V",
            "setSpeakerVolume(F)V",
            "setVideoInputDevice(I)V",
            "setVideoOutputSink(Ljava/lang/String;Lcom/hammerandchisel/libdiscord/Discord\$VideoFrameCallback;)V",
            "signalVideoOutputSinkReady(Ljava/lang/String;)V",
        )
        val natives = discord.declaredMethods.filter { Modifier.isNative(it.modifiers) }
        assertEquals(expected, natives.mapTo(mutableSetOf()) { it.descriptor() })
        assertTrue(natives.all { Modifier.isNative(it.modifiers) })
    }

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
