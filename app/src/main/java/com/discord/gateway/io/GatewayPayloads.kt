package com.discord.gateway.io

import com.discord.api.activity.Activity
import com.discord.gateway.opcodes.Opcode
import com.google.gson.annotations.SerializedName

data class Incoming(
    val type: String?,
    val seq: Int?,
    val opcode: Opcode,
    val data: Any?,
)

class Outgoing(val op: Int, private val d: Any? = null) {
    constructor(opcode: Opcode, d: Any? = null) : this(opcode.apiInt, d)
}

data class Hello(
    @SerializedName("heartbeat_interval") val heartbeatInterval: Int,
    @SerializedName("_trace") val trace: List<String>?,
)

sealed class OutgoingPayload private constructor() {
    class ApplicationCommandRequest(
        val guildId: Long,
        val query: String? = null,
        val offset: Int? = null,
        val limit: Int? = null,
        val applications: Boolean = false,
        val nonce: String,
        val commandIds: List<String>? = null,
    ) : OutgoingPayload() {
        constructor(
            guildId: Long,
            nonce: String,
            applications: Boolean,
            limit: Int?,
            query: String?,
            offset: Int?,
            commandIds: List<String>?,
        ) : this(guildId, query, offset, limit, applications, nonce, commandIds)
    }


    class CreateStream(
        val type: String,
        val channelId: Long,
        val guildId: Long?,
        val preferredRegion: String?,
    )

    class DeleteStream(val streamKey: String) : OutgoingPayload()

    class ForumUnreadsRequest(
        val guildId: Long,
        val channelId: Long,
        val threads: List<ForumUnreadsRequestThread>,
    ) : OutgoingPayload()

    class ForumUnreadsRequestThread(
        val threadId: Long,
        val ackMessageId: Long,
    )

    data class GuildMembersRequest(
        private val guildId: List<Long>,
        private val query: String? = null,
        private val userIds: List<Long>? = null,
        private val limit: Int? = null,
        val presences: Boolean = true,
    ) : OutgoingPayload()

    class GuildSubscriptions(
        val channels: Map<Long, List<List<Int>>>? = null,
        val typing: Boolean? = null,
        val activities: Boolean? = null,
        val members: List<Long>? = null,
        val threads: Boolean? = null,
        val threadMemberLists: List<Long>?,
    ) : OutgoingPayload()

    class GuildSubscriptionsUpdate(
        val guildId: Long,
        val typing: Boolean?,
        val activities: Boolean?,
        val members: List<Long>?,
        val channels: Map<Long, List<List<Int>>>?,
        val threads: Boolean?,
        val threadMemberLists: List<Long>?,
    ) : OutgoingPayload()

    class Identify(
        private val token: String,
        private val compress: Boolean,
        private val capabilities: Long,
        private val properties: Map<String, Any?>,
        private val clientState: IdentifyClientState?,
    ) : OutgoingPayload() {
        override fun toString(): String =
            "Identify(token=<redacted>, compress=$compress, " +
                "capabilities=$capabilities, properties=$properties, clientState=$clientState)"
    }

    data class IdentifyClientState(
        val guildVersions: Map<Long, Long>,
        val highestLastMessageId: Long,
        val readStateVersion: Int,
        val userGuildSettingsVersion: Int,
        val userSettingsVersion: Int? = null,
        val privateChannelsVersion: Long? = null,
        val apiCodeVersion: Int? = null,
        val initialGuildId: Long? = null,
    ) {

        override fun toString(): String = "IdentifyClientState(guildVersions=${guildVersions.size} guilds, " +
            "highestLastMessageId=$highestLastMessageId, readStateVersion=$readStateVersion, " +
            "userGuildSettingsVersion=$userGuildSettingsVersion)"
    }

    data class PresenceUpdate(
        private val status: String?,
        private val since: Long?,
        private val activities: List<Activity> = emptyList(),
        private val afk: Boolean? = false,
    ) : OutgoingPayload()

    class Resume(
        private val token: String?,
        private val sessionId: String?,
        private val seq: Int,
    ) : OutgoingPayload() {
        override fun toString(): String = "Resume(token=<redacted>, sessionId=<redacted>, seq=$seq)"
    }

    class StreamPing(val streamKey: String) : OutgoingPayload()

    data class VoiceStateUpdate(
        val guildId: Long?,
        val channelId: Long?,
        val selfMute: Boolean,
        val selfDeaf: Boolean,
        val selfVideo: Boolean,
        val preferredRegion: String? = null,
    ) : OutgoingPayload()

    data class SoundboardSoundsRequest(val guildIds: List<Long>) : OutgoingPayload()

    data class DeletedEntityIdsRequest(
        val guildId: Long,
        val channelIdsHash: String,
        val roleIdsHash: String,
        val emojiIdsHash: String,
        val stickerIdsHash: String,
    ) : OutgoingPayload()

    data class VoiceStateUpdateNoPreferredRegion(
        val guildId: Long?,
        val channelId: Long?,
        val selfMute: Boolean,
        val selfDeaf: Boolean,
        val selfVideo: Boolean,
    ) : OutgoingPayload()

    class WatchStream(val streamKey: String) : OutgoingPayload()
}

typealias ApplicationCommandRequest = OutgoingPayload.ApplicationCommandRequest
typealias CreateStream = OutgoingPayload.CreateStream
typealias DeleteStream = OutgoingPayload.DeleteStream
typealias ForumUnreadsRequest = OutgoingPayload.ForumUnreadsRequest
typealias ForumUnreadsRequestThread = OutgoingPayload.ForumUnreadsRequestThread
typealias GuildMembersRequest = OutgoingPayload.GuildMembersRequest
typealias GuildSubscriptions = OutgoingPayload.GuildSubscriptions
typealias GuildSubscriptionsUpdate = OutgoingPayload.GuildSubscriptionsUpdate
typealias Identify = OutgoingPayload.Identify
typealias IdentifyClientState = OutgoingPayload.IdentifyClientState
typealias PresenceUpdate = OutgoingPayload.PresenceUpdate
typealias Resume = OutgoingPayload.Resume
typealias SoundboardSoundsRequest = OutgoingPayload.SoundboardSoundsRequest
typealias DeletedEntityIdsRequest = OutgoingPayload.DeletedEntityIdsRequest
typealias StreamPing = OutgoingPayload.StreamPing
typealias VoiceStateUpdate = OutgoingPayload.VoiceStateUpdate
typealias VoiceStateUpdateNoPreferredRegion = OutgoingPayload.VoiceStateUpdateNoPreferredRegion
typealias WatchStream = OutgoingPayload.WatchStream
