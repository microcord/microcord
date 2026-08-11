package com.discord.gateway.io

import com.discord.api.auth.AuthSessionChange
import com.discord.api.channel.Channel
import com.discord.api.channel.ChannelPinsUpdate
import com.discord.api.channel.ChannelRecipient
import com.discord.api.commands.ApplicationCommandAutocompleteResult
import com.discord.api.commands.GuildApplicationCommandIndexUpdate
import com.discord.api.embeddedactivities.EmbeddedActivityInboundUpdate
import com.discord.api.embeddedactivities.EmbeddedActivityInstance
import com.discord.api.emoji.GuildEmojisUpdate
import com.discord.api.forum.ForumUnreads
import com.discord.api.friendsuggestions.FriendSuggestion
import com.discord.api.friendsuggestions.FriendSuggestionDelete
import com.discord.api.guild.Guild
import com.discord.api.guild.PassiveUpdateV2
import com.discord.api.guildjoinrequest.GuildJoinRequestCreateOrUpdate
import com.discord.api.guildjoinrequest.GuildJoinRequestDelete
import com.discord.api.guildmember.GuildMember
import com.discord.api.guildmember.GuildMemberRemove
import com.discord.api.guildmember.GuildMembersChunk
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventUserUpdate
import com.discord.api.interaction.InteractionModalCreate
import com.discord.api.interaction.InteractionStateUpdate
import com.discord.api.message.Message
import com.discord.api.message.PollVoteAddMany
import com.discord.api.message.PollVoteEvent
import com.discord.api.message.reaction.MessageReactionAddMany
import com.discord.api.message.reaction.MessageReactionUpdate
import com.discord.api.presence.Presence
import com.discord.api.requiredaction.UserRequiredActionUpdate
import com.discord.api.role.GuildRoleCreate
import com.discord.api.role.GuildRoleDelete
import com.discord.api.role.GuildRoleUpdate
import com.discord.api.stageinstance.StageInstance
import com.discord.api.sticker.GuildStickersUpdate
import com.discord.api.soundboard.SoundboardSounds
import com.discord.api.thread.ThreadMemberListUpdate
import com.discord.api.thread.ThreadMemberUpdate
import com.discord.api.thread.ThreadMembersUpdate
import com.discord.api.user.User
import com.discord.api.user.TypingUser
import com.discord.api.voice.server.VoiceServer
import com.discord.api.voice.state.VoiceState
import com.discord.api.voice.state.VoiceStateUpdateBatch
import com.discord.gateway.opcodes.Opcode
import com.discord.models.domain.ModelBan
import com.discord.models.domain.ModelCall
import com.discord.models.domain.ModelChannelUnreadUpdate
import com.discord.models.domain.ModelGuildIntegration
import com.discord.models.domain.ModelGuildMemberListUpdate
import com.discord.models.domain.ModelMessageDelete
import com.discord.api.notification.AccountNotificationSettings
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadySupplemental
import com.discord.models.domain.ModelReadState
import com.discord.models.domain.ModelSession
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.domain.ModelUserNote
import com.discord.models.domain.ModelUserSettings
import com.discord.models.domain.ReadyGuild
import com.discord.models.domain.BackgroundSync
import com.discord.models.domain.StreamCreateOrUpdate
import com.discord.models.domain.StreamDelete
import com.discord.models.domain.StreamServerUpdate
import com.discord.models.domain.UserSettingsProtoUpdate
import com.discord.models.thread.dto.ModelThreadListSync
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.StringReader

/**
 * Turns a gateway frame into a typed payload.
 *
 * Every dispatch type the reference client routes has a branch here. Types the reference does not
 * send are deliberately absent — an unrecognised type falls through to [DISPATCH_UNPARSED], which
 * the socket treats as "nothing to hand to the stores" rather than silently forwarding raw JSON.
 */
class IncomingParser(private val gson: Gson) {

    /** Marker for a dispatch type this client does not consume. */
    object DISPATCH_UNPARSED

    fun parse(json: String): Incoming = parse { JsonReader(StringReader(json)) }

    fun parse(json: ByteArray): Incoming = parse {
        JsonReader(InputStreamReader(ByteArrayInputStream(json), Charsets.UTF_8))
    }

    private fun parse(reader: () -> JsonReader): Incoming {
        readIncoming(reader())?.let { return it }
        val metadata = readGsonMetadata(reader())
        val data = parseData(metadata) { readData(reader(), metadata) }
        return Incoming(metadata.type, metadata.sequence, metadata.opcode, data)
    }

    /** Parses normal Gateway envelopes once; unusual envelopes with `d` first use the safe fallback. */
    private fun readIncoming(reader: JsonReader): Incoming? = reader.use {
        var type: String? = null
        var sequence: Int? = null
        var opcodeValue: Int? = null
        var data: Any? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "t" -> type = reader.nextStringOrNull()
                "s" -> sequence = reader.nextIntOrNull()
                "op" -> opcodeValue = reader.nextIntOrNull()
                "d" -> {
                    val opcode = opcodeValue?.let(Opcode::fromApiInt) ?: return null
                    if (opcode == Opcode.DISPATCH && type == null) return null
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                    } else if (opcode == Opcode.DISPATCH && type in BODYLESS_EVENTS) {
                        reader.skipValue()
                        data = Unit
                    } else {
                        data = readDataValue(reader, Metadata(type, sequence, opcode))
                        if (data === DISPATCH_UNPARSED) reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val opcode = Opcode.fromApiInt(opcodeValue ?: -1)
        if (opcode == Opcode.DISPATCH && type in BODYLESS_EVENTS) data = Unit
        Incoming(type, sequence, opcode, data)
    }

    private inline fun parseData(metadata: Metadata, parser: () -> Any?): Any? =
        if (metadata.opcode == Opcode.DISPATCH && metadata.type in BODYLESS_EVENTS) Unit else parser()

    /** Reads the small envelope fields without materialising the potentially huge `d` tree. */
    private fun readGsonMetadata(reader: JsonReader): Metadata = reader.use {
        var type: String? = null
        var sequence: Int? = null
        var opcode = -1
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "t" -> type = reader.nextStringOrNull()
                "s" -> sequence = reader.nextIntOrNull()
                "op" -> opcode = reader.nextIntOrNull() ?: -1
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        Metadata(type, sequence, Opcode.fromApiInt(opcode))
    }

    /** Replays the envelope and streams only `d` into the adapter selected by [metadata]. */
    private fun readData(reader: JsonReader, metadata: Metadata): Any? = reader.use {
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() != "d") {
                reader.skipValue()
                continue
            }
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                return@use null
            }
            return@use readDataValue(reader, metadata)
        }
        null
    }

    private fun readDataValue(reader: JsonReader, metadata: Metadata): Any? = when (metadata.opcode) {
        Opcode.DISPATCH -> parseDispatch(metadata.type, reader)
        Opcode.HELLO -> gson.fromJson(reader, Hello::class.java)
        Opcode.INVALID_SESSION -> reader.nextBoolean()
        else -> JsonParser.parseReader(reader)
    }

    /**
     * @param data null when the frame carried no `d`. Only the bodyless "refetch me" events tolerate
     *   that; everything else yields null so the socket does not publish an empty payload.
     */
    private fun parseDispatch(type: String?, data: JsonReader): Any? {
        return try {
            parseDispatchBody(type, data)
        } catch (error: JsonParseException) {
            // The socket only sees the throwable; without the dispatch type its log cannot say which
            // event's model is wrong.
            throw JsonParseException("$type: ${error.message}", error)
        }
    }

    private fun parseDispatchBody(type: String?, data: JsonReader): Any? = when (type) {
        "READY" -> gson.fromJson(data, ModelPayload::class.java)
        "READY_SUPPLEMENTAL" -> gson.fromJson(data, ModelReadySupplemental::class.java)
        "INITIAL_GUILD" -> gson.fromJson(data, ReadyGuild::class.java)

        // Current user
        "USER_UPDATE" -> gson.fromJson(data, User::class.java)
        "USER_SETTINGS_UPDATE" -> gson.fromJson(data, ModelUserSettings::class.java)
        "USER_SETTINGS_PROTO_UPDATE" -> gson.fromJson(data, UserSettingsProtoUpdate::class.java)
        "USER_GUILD_SETTINGS_UPDATE" -> gson.fromJson(data, ModelNotificationSettings::class.java)
        "NOTIFICATION_SETTINGS_UPDATE" -> gson.fromJson(data, AccountNotificationSettings::class.java)
        "USER_NOTE_UPDATE" -> gson.fromJson(data, ModelUserNote.Update::class.java)
        "USER_REQUIRED_ACTION_UPDATE" -> gson.fromJson(data, UserRequiredActionUpdate::class.java)
        "AUTH_SESSION_CHANGE" -> gson.fromJson(data, AuthSessionChange::class.java)
        "SESSIONS_REPLACE" -> gson.fromJson<List<ModelSession>>(data, sessionListType)

        // Relationships and friend suggestions
        "RELATIONSHIP_ADD", "RELATIONSHIP_UPDATE", "RELATIONSHIP_REMOVE" ->
            gson.fromJson(data, ModelUserRelationship::class.java)
        "FRIEND_SUGGESTION_CREATE" -> gson.fromJson(data, FriendSuggestion::class.java)
        "FRIEND_SUGGESTION_DELETE" -> gson.fromJson(data, FriendSuggestionDelete::class.java)

        // Guilds
        "GUILD_CREATE" -> parseGuildCreate(JsonParser.parseReader(data))
        "BACKGROUND_SYNC" -> gson.fromJson(JsonParser.parseReader(data), BackgroundSync::class.java)
        "DELETED_ENTITY_IDS" -> gson.fromJson(data, DeletedEntityIds::class.java)
        "GUILD_UPDATE", "GUILD_DELETE" -> gson.fromJson(data, Guild::class.java)
        "GUILD_BAN_ADD", "GUILD_BAN_REMOVE" -> gson.fromJson(data, ModelBan::class.java)
        "GUILD_EMOJIS_UPDATE" -> gson.fromJson(data, GuildEmojisUpdate::class.java)
        "GUILD_STICKERS_UPDATE" -> gson.fromJson(data, GuildStickersUpdate::class.java)
        "SOUNDBOARD_SOUNDS" -> gson.fromJson(data, SoundboardSounds::class.java)
        "GUILD_INTEGRATIONS_UPDATE" -> gson.fromJson(data, ModelGuildIntegration.Update::class.java)
        "GUILD_APPLICATION_COMMAND_INDEX_UPDATE" ->
            gson.fromJson(data, GuildApplicationCommandIndexUpdate::class.java)
        "APPLICATION_COMMAND_AUTOCOMPLETE_RESPONSE" ->
            gson.fromJson(data, ApplicationCommandAutocompleteResult::class.java)

        // Guild membership
        "GUILD_MEMBER_ADD", "GUILD_MEMBER_UPDATE" -> gson.fromJson(data, GuildMember::class.java)
        "GUILD_MEMBER_REMOVE" -> gson.fromJson(data, GuildMemberRemove::class.java)
        "GUILD_MEMBERS_CHUNK" -> gson.fromJson(data, GuildMembersChunk::class.java)
        "GUILD_MEMBER_LIST_UPDATE" -> gson.fromJson(data, ModelGuildMemberListUpdate::class.java)
        "GUILD_JOIN_REQUEST_CREATE", "GUILD_JOIN_REQUEST_UPDATE" ->
            gson.fromJson(data, GuildJoinRequestCreateOrUpdate::class.java)
        "GUILD_JOIN_REQUEST_DELETE" -> gson.fromJson(data, GuildJoinRequestDelete::class.java)

        // Guild roles
        "GUILD_ROLE_CREATE" -> gson.fromJson(data, GuildRoleCreate::class.java)
        "GUILD_ROLE_UPDATE" -> gson.fromJson(data, GuildRoleUpdate::class.java)
        "GUILD_ROLE_DELETE" -> gson.fromJson(data, GuildRoleDelete::class.java)

        // Guild scheduled events
        "GUILD_SCHEDULED_EVENT_CREATE", "GUILD_SCHEDULED_EVENT_UPDATE", "GUILD_SCHEDULED_EVENT_DELETE" ->
            gson.fromJson(data, GuildScheduledEvent::class.java)
        "GUILD_SCHEDULED_EVENT_USER_ADD", "GUILD_SCHEDULED_EVENT_USER_REMOVE" ->
            gson.fromJson(data, GuildScheduledEventUserUpdate::class.java)

        // Channels and threads
        "CHANNEL_CREATE", "CHANNEL_UPDATE", "CHANNEL_DELETE",
        "THREAD_CREATE", "THREAD_UPDATE", "THREAD_DELETE" -> gson.fromJson(data, Channel::class.java)
        "CHANNEL_RECIPIENT_ADD", "CHANNEL_RECIPIENT_REMOVE" -> gson.fromJson(data, ChannelRecipient::class.java)
        "CHANNEL_UNREAD_UPDATE" -> gson.fromJson(data, ModelChannelUnreadUpdate::class.java)
        "PASSIVE_UPDATE_V2" -> gson.fromJson(data, PassiveUpdateV2::class.java)
        "CHANNEL_PINS_UPDATE" -> gson.fromJson(data, ChannelPinsUpdate::class.java)
        "THREAD_LIST_SYNC" -> gson.fromJson(data, ModelThreadListSync::class.java)
        "THREAD_MEMBER_UPDATE" -> gson.fromJson(data, ThreadMemberUpdate::class.java)
        "THREAD_MEMBERS_UPDATE" -> gson.fromJson(data, ThreadMembersUpdate::class.java)
        "THREAD_MEMBER_LIST_UPDATE" -> gson.fromJson(data, ThreadMemberListUpdate::class.java)
        "FORUM_UNREADS" -> gson.fromJson(data, ForumUnreads::class.java)

        // Messages
        "MESSAGE_CREATE", "MESSAGE_UPDATE" -> gson.fromJson(data, Message::class.java)
        "MESSAGE_DELETE", "MESSAGE_DELETE_BULK" -> gson.fromJson(data, ModelMessageDelete::class.java)
        "MESSAGE_ACK" -> gson.fromJson(data, ModelReadState::class.java)
        // All four reaction events share one payload type. REMOVE_ALL carries no user or emoji and
        "MESSAGE_REACTION_ADD_MANY" -> gson.fromJson(data, MessageReactionAddMany::class.java)
        // REMOVE_EMOJI carries no user, so those members are absent on the wire for them.
        "MESSAGE_REACTION_ADD", "MESSAGE_REACTION_REMOVE",
        "MESSAGE_REACTION_REMOVE_ALL", "MESSAGE_REACTION_REMOVE_EMOJI" ->
            gson.fromJson(data, MessageReactionUpdate::class.java)
        "MESSAGE_POLL_VOTE_ADD_MANY" -> gson.fromJson(data, PollVoteAddMany::class.java)
        "MESSAGE_POLL_VOTE_ADD", "MESSAGE_POLL_VOTE_REMOVE" ->
            gson.fromJson(data, PollVoteEvent::class.java)

        // Presence and typing
        "PRESENCE_UPDATE" -> gson.fromJson(data, Presence::class.java)
        "PRESENCES_REPLACE" -> gson.fromJson<List<Presence>>(data, presenceListType)
        "TYPING_START" -> gson.fromJson(data, TypingUser::class.java)

        // Voice, calls and streams
        "VOICE_STATE_UPDATE" -> gson.fromJson(data, VoiceState::class.java)
        "VOICE_STATE_UPDATE_BATCH" -> gson.fromJson(data, VoiceStateUpdateBatch::class.java)
        "VOICE_SERVER_UPDATE" -> gson.fromJson(data, VoiceServer::class.java)
        "CALL_CREATE", "CALL_UPDATE", "CALL_DELETE" -> gson.fromJson(data, ModelCall::class.java)
        "STREAM_CREATE", "STREAM_UPDATE" -> gson.fromJson(data, StreamCreateOrUpdate::class.java)
        "STREAM_DELETE" -> gson.fromJson(data, StreamDelete::class.java)
        "STREAM_SERVER_UPDATE" -> gson.fromJson(data, StreamServerUpdate::class.java)

        // Stage instances, interactions and embedded activities
        "STAGE_INSTANCE_CREATE", "STAGE_INSTANCE_UPDATE", "STAGE_INSTANCE_DELETE" ->
            gson.fromJson(data, StageInstance::class.java)
        "INTERACTION_CREATE", "INTERACTION_SUCCESS", "INTERACTION_FAILURE" ->
            gson.fromJson(data, InteractionStateUpdate::class.java)
        "INTERACTION_MODAL_CREATE" -> gson.fromJson(data, InteractionModalCreate::class.java)
        "EMBEDDED_ACTIVITY_UPDATE" -> gson.fromJson(data, EmbeddedActivityInboundUpdate::class.java)
        "EMBEDDED_ACTIVITY_UPDATE_V2" -> gson.fromJson(data, EmbeddedActivityInstance::class.java)

        else -> DISPATCH_UNPARSED
    }

    private fun parseGuildCreate(data: JsonElement): Any {
        if (!data.asJsonObject.has("data_mode")) return gson.fromJson(data, Guild::class.java)
        return gson.fromJson(data, ReadyGuild::class.java)
    }

    private fun JsonReader.nextStringOrNull(): String? {
        if (peek() != JsonToken.NULL) return nextString()
        nextNull()
        return null
    }

    private fun JsonReader.nextIntOrNull(): Int? {
        if (peek() != JsonToken.NULL) return nextInt()
        nextNull()
        return null
    }

    private data class Metadata(
        val type: String?,
        val sequence: Int?,
        val opcode: Opcode,
    )

    companion object {
        /**
         * Dispatch types whose body the client skips outright, yielding [Unit].
         *
         * `USER_SUBSCRIPTIONS_UPDATE` and `USER_PAYMENT_SOURCES_UPDATE` are deliberately absent:
         * those arrive with no `d` at all and the socket substitutes [Unit] for them.
         */
        val BODYLESS_EVENTS = setOf(
            "USER_CONNECTIONS_UPDATE",
            "USER_CONNECTIONS_INTEGRATION_JOINING",
        )

        private val sessionListType = object : TypeToken<List<ModelSession>>() {}.type
        private val presenceListType = object : TypeToken<List<Presence>>() {}.type
    }
}
