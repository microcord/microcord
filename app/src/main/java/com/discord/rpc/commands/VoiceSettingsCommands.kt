package com.discord.rpc.commands

import com.discord.rpc.OAuth2Scopes
import com.discord.rpc.RpcCommand
import com.discord.rpc.RpcCommands
import com.discord.rpc.RpcScopeConfig
import com.discord.stores.StoreMediaSettings
import com.discord.stores.StoreStream
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import org.json.JSONArray
import org.json.JSONObject

/** Device lists and `mode.shortcut` stay empty, as the reference leaves them on mobile. */
fun deprecatedVoiceSettings(): JSONObject {
    val settings = StoreStream.getMediaSettings().getVoiceConfigurationBlocking()
    return JSONObject()
        .put(
            "input",
            JSONObject()
                .put("available_devices", JSONArray())
                .put("device_id", "default")
                .put("volume", 100),
        )
        .put(
            "output",
            JSONObject()
                .put("available_devices", JSONArray())
                .put("device_id", "default")
                .put("volume", settings.outputVolume),
        )
        .put(
            "mode",
            JSONObject()
                .put("type", inputModeName(settings.inputMode))
                .put("auto_threshold", settings.automaticVad)
                .put("threshold", settings.sensitivity)
                .put("shortcut", JSONArray())
                .put("delay", 0),
        )
        .put("automatic_gain_control", settings.automaticGainControl)
        .put("echo_cancellation", settings.echoCancellation)
        .put("noise_suppression", settings.noiseProcessing != StoreMediaSettings.NoiseProcessing.None)
        .put("qos", true)
        .put("silence_warning", false)
        .put("deaf", settings.isSelfDeafened)
        .put("mute", settings.isSelfMuted())
}

fun voiceSettings(): JSONObject {
    val settings = StoreStream.getMediaSettings().getVoiceConfigurationBlocking()
    val volumes = JSONObject().apply {
        settings.userOutputVolumes.forEach { (userId, volume) -> put(userId.toString(), volume) }
    }
    val mutes = JSONArray().apply {
        settings.mutedUsers.filterValues { it }.keys.forEach { put(it.toString()) }
    }
    return JSONObject()
        .put("input_mode", JSONObject().put("type", inputModeName(settings.inputMode)).put("shortcut", ""))
        .put("local_mutes", mutes)
        .put("local_volumes", volumes)
        .put("self_mute", settings.isSelfMuted())
        .put("self_deaf", settings.isSelfDeafened)
}

private fun inputModeName(mode: MediaEngineConnection.InputMode): String = when (mode) {
    MediaEngineConnection.InputMode.PUSH_TO_TALK -> "PUSH_TO_TALK"
    else -> "VOICE_ACTIVITY"
}

val voiceSettingsCommands: Map<String, RpcCommand> = mapOf(
    RpcCommands.GET_VOICE_SETTINGS to syncRpcCommand(
        scope = RpcScopeConfig.Any(listOf(OAuth2Scopes.RPC, OAuth2Scopes.RPC_VOICE_READ)),
    ) {
        deprecatedVoiceSettings()
    },
)
