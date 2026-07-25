package com.discord.gateway.io

import com.discord.api.activity.Activity
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.discord.gateway.opcodes.Opcode

data class Incoming(
    val type: String?,
    val seq: Int?,
    val opcode: Opcode,
    val data: Any?,
)

data class Outgoing<T>(val op: Int, val d: T? = null)

data class Hello(
    @SerializedName("heartbeat_interval") val heartbeatInterval: Int,
    @SerializedName("_trace") val trace: List<String>?,
)

data class IdentifyClientState(
    @SerializedName("guild_hashes") val guildHashes: Map<Long, Array<String>>,
    @SerializedName("highest_last_message_id") val highestLastMessageId: Long,
    @SerializedName("read_state_version") val readStateVersion: Int,
    @SerializedName("useruser_guild_settings_version") val useruserGuildSettingsVersion: Int,
)

data class Identify(
    val token: String,
    @SerializedName("large_threshold") val largeThreshold: Int,
    val compress: Boolean,
    val capabilities: Long,
    val properties: Map<String, Any>,
    @SerializedName("client_state") val clientState: IdentifyClientState?,
) {
    override fun toString(): String = "Identify(token=<redacted>, largeThreshold=$largeThreshold, compress=$compress, capabilities=$capabilities, properties=$properties, clientState=$clientState)"
}

data class Resume(
    val token: String?,
    @SerializedName("session_id") val sessionId: String?,
    val seq: Int,
) {
    override fun toString(): String = "Resume(token=<redacted>, sessionId=$sessionId, seq=$seq)"
}

data class PresenceUpdate(
    val status: String?,
    val since: Long?,
    val activities: List<Activity> = emptyList(),
    val afk: Boolean? = false,
)
