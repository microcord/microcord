package com.discord.gateway.io

import com.discord.api.activity.Activity
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
    @SerializedName("user_guild_settings_version") val userGuildSettingsVersion: Int,
) {
    override fun equals(other: Any?): Boolean = this === other || (
        other is IdentifyClientState &&
            highestLastMessageId == other.highestLastMessageId &&
            readStateVersion == other.readStateVersion &&
            userGuildSettingsVersion == other.userGuildSettingsVersion &&
            guildHashes.keys == other.guildHashes.keys &&
            guildHashes.all { (id, hashes) -> other.guildHashes[id].contentEquals(hashes) }
        )

    override fun hashCode(): Int {
        var result = guildHashes.entries.sumOf { (id, hashes) -> id.hashCode() * 31 + hashes.contentHashCode() }
        result = 31 * result + highestLastMessageId.hashCode()
        result = 31 * result + readStateVersion
        result = 31 * result + userGuildSettingsVersion
        return result
    }

    override fun toString(): String = "IdentifyClientState(guildHashes=${guildHashes.size} guilds, " +
        "highestLastMessageId=$highestLastMessageId, readStateVersion=$readStateVersion, " +
        "userGuildSettingsVersion=$userGuildSettingsVersion)"
}

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

/** [Opcode.VOICE_STATE_UPDATE]. A null [channelId] leaves the current voice channel. */
data class VoiceStateUpdate(
    @SerializedName("guild_id") val guildId: Long?,
    @SerializedName("channel_id") val channelId: Long?,
    @SerializedName("self_mute") val selfMute: Boolean,
    @SerializedName("self_deaf") val selfDeaf: Boolean,
    @SerializedName("self_video") val selfVideo: Boolean,
    @SerializedName("preferred_region") val preferredRegion: String?,
)

/**
 * [Opcode.VOICE_STATE_UPDATE] without `preferred_region`.
 *
 * The field must be *absent*, not null: sending `preferred_region: null` asks the backend to clear
 * the pinned region, which is different from not expressing a preference.
 */
data class VoiceStateUpdateNoPreferredRegion(
    @SerializedName("guild_id") val guildId: Long?,
    @SerializedName("channel_id") val channelId: Long?,
    @SerializedName("self_mute") val selfMute: Boolean,
    @SerializedName("self_deaf") val selfDeaf: Boolean,
    @SerializedName("self_video") val selfVideo: Boolean,
)

/** [Opcode.VOICE_SERVER_PING] carries no body; this exists so the op has a typed send path. */
object VoiceServerPing

/** [Opcode.CALL_CONNECT]: subscribe to call state for a private/group channel. */
data class CallConnect(@SerializedName("channel_id") val channelId: Long)

/** [Opcode.REQUEST_GUILD_MEMBERS]. */
data class GuildMembersRequest(
    @SerializedName("guild_id") val guildId: List<Long>,
    val query: String?,
    @SerializedName("user_ids") val userIds: List<Long>?,
    val limit: Int?,
    val presences: Boolean = false,
)

/** [Opcode.GUILD_SUBSCRIPTIONS] as stored per guild before it is addressed to one. */
data class GuildSubscriptions(
    val channels: Map<Long, List<List<Int>>>?,
    val typing: Boolean?,
    val activities: Boolean?,
    val members: List<Long>?,
    val threads: Boolean?,
    @SerializedName("thread_member_lists") val threadMemberLists: List<Long>?,
)

/** [Opcode.GUILD_SUBSCRIPTIONS] as sent on the wire, addressed to one guild. */
data class GuildSubscriptionsUpdate(
    @SerializedName("guild_id") val guildId: Long,
    val typing: Boolean?,
    val activities: Boolean?,
    val members: List<Long>?,
    val channels: Map<Long, List<List<Int>>>?,
    val threads: Boolean?,
    @SerializedName("thread_member_lists") val threadMemberLists: List<Long>?,
)

/** [Opcode.STREAM_CREATE]: begin broadcasting. [type] is `guild` or `call`. */
data class CreateStream(
    val type: String,
    @SerializedName("channel_id") val channelId: Long,
    @SerializedName("guild_id") val guildId: Long?,
    @SerializedName("preferred_region") val preferredRegion: String?,
)

/** [Opcode.STREAM_WATCH]: begin watching someone else's stream. */
data class WatchStream(@SerializedName("stream_key") val streamKey: String)

/** [Opcode.STREAM_DELETE]: stop broadcasting or stop watching. */
data class DeleteStream(@SerializedName("stream_key") val streamKey: String)

/** [Opcode.OPCODE_STREAM_PING]: keep-alive for an active stream. */
data class StreamPing(@SerializedName("stream_key") val streamKey: String)

/** [Opcode.REQUEST_GUILD_APPLICATION_COMMANDS]; answered by `GUILD_APPLICATION_COMMANDS_UPDATE`. */
data class ApplicationCommandRequest(
    @SerializedName("guild_id") val guildId: Long,
    val nonce: String,
    val applications: Boolean,
    val limit: Int?,
    val query: String?,
    val offset: Int?,
    @SerializedName("command_ids") val commandIds: List<String>?,
)

/** One thread of a [ForumUnreadsRequest], paired with the last message the client acknowledged. */
data class ForumUnreadsRequestThread(
    @SerializedName("thread_id") val threadId: Long,
    @SerializedName("ack_message_id") val ackMessageId: Long,
)

/** [Opcode.REQUEST_FORUM_UNREADS]; answered by `FORUM_UNREADS`. */
data class ForumUnreadsRequest(
    @SerializedName("guild_id") val guildId: Long,
    @SerializedName("channel_id") val channelId: Long,
    val threads: List<ForumUnreadsRequestThread>,
)
