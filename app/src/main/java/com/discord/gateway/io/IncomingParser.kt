package com.discord.gateway.io

import com.discord.api.channel.Channel
import com.discord.api.channel.ChannelRecipient
import com.discord.api.commands.ApplicationCommandAutocompleteResult
import com.discord.api.commands.GuildApplicationCommands
import com.discord.api.commands.GuildApplicationCommandIndexUpdate
import com.discord.api.embeddedactivities.EmbeddedActivityInboundUpdate
import com.discord.api.embeddedactivities.EmbeddedActivityInstance
import com.discord.api.emoji.GuildEmojisUpdate
import com.discord.api.forum.ForumUnreads
import com.discord.api.friendsuggestions.FriendSuggestion
import com.discord.api.friendsuggestions.FriendSuggestionDelete
import com.discord.api.guild.Guild
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
import com.discord.api.message.reaction.MessageReactionUpdate
import com.discord.api.presence.Presence
import com.discord.api.requiredaction.UserRequiredActionUpdate
import com.discord.api.role.GuildRoleCreate
import com.discord.api.role.GuildRoleDelete
import com.discord.api.role.GuildRoleUpdate
import com.discord.api.stageinstance.StageInstance
import com.discord.api.sticker.GuildStickersUpdate
import com.discord.api.thread.ThreadMemberListUpdate
import com.discord.api.thread.ThreadMemberUpdate
import com.discord.api.thread.ThreadMembersUpdate
import com.discord.api.user.User
import com.discord.api.user.TypingUser
import com.discord.api.voice.server.VoiceServer
import com.discord.api.voice.state.VoiceState
import com.discord.gateway.opcodes.Opcode
import com.discord.models.domain.ModelBan
import com.discord.models.domain.ModelCall
import com.discord.models.domain.ModelChannelUnreadUpdate
import com.discord.models.domain.ModelGuildIntegration
import com.discord.models.domain.ModelGuildMemberListUpdate
import com.discord.models.domain.ModelMessageDelete
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadState
import com.discord.models.domain.ModelSession
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.domain.ModelUserNote
import com.discord.models.domain.ModelUserSettings
import com.discord.models.domain.StreamCreateOrUpdate
import com.discord.models.domain.StreamDelete
import com.discord.models.domain.StreamServerUpdate
import com.discord.models.domain.UserSettingsProtoUpdate
import com.discord.models.thread.dto.ModelThreadListSync
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

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

    fun parse(json: String): Incoming {
        val envelope = JsonParser.parseString(json).asJsonObject
        val type = envelope["t"]?.takeUnless(JsonElement::isJsonNull)?.asString
        val seq = envelope["s"]?.takeUnless(JsonElement::isJsonNull)?.asInt
        val opcode = Opcode.fromApiInt(envelope["op"]?.asInt ?: -1)
        val rawData = envelope["d"]
        val body = rawData?.takeUnless(JsonElement::isJsonNull)
        val data = when {
            opcode == Opcode.DISPATCH -> parseDispatch(type, body)
            body == null -> null
            opcode == Opcode.HELLO -> gson.fromJson(body, Hello::class.java)
            opcode == Opcode.INVALID_SESSION -> body.asBoolean
            else -> body.deepCopy()
        }
        return Incoming(type, seq, opcode, data)
    }

    /**
     * @param data null when the frame carried no `d`. Only the bodyless "refetch me" events tolerate
     *   that; everything else yields null so the socket does not publish an empty payload.
     */
    private fun parseDispatch(type: String?, data: JsonElement?): Any? {
        // Pure "refetch me" nudges: whatever body they carry is discarded.
        if (type in BODYLESS_EVENTS) return Unit
        if (data == null) return null
        return parseDispatchBody(type, data)
    }

    private fun parseDispatchBody(type: String?, data: JsonElement): Any? = when (type) {
        "READY" -> gson.fromJson(data, ModelPayload::class.java)

        // Current user
        "USER_UPDATE" -> gson.fromJson(data, User::class.java)
        "USER_SETTINGS_UPDATE" -> gson.fromJson(data, ModelUserSettings::class.java)
        "USER_SETTINGS_PROTO_UPDATE" -> gson.fromJson(data, UserSettingsProtoUpdate::class.java)
        "USER_GUILD_SETTINGS_UPDATE" -> gson.fromJson(data, ModelNotificationSettings::class.java)
        "USER_NOTE_UPDATE" -> gson.fromJson(data, ModelUserNote.Update::class.java)
        "USER_REQUIRED_ACTION_UPDATE" -> gson.fromJson(data, UserRequiredActionUpdate::class.java)
        "SESSIONS_REPLACE" -> gson.fromJson<List<ModelSession>>(data, sessionListType)

        // Relationships and friend suggestions
        "RELATIONSHIP_ADD", "RELATIONSHIP_UPDATE", "RELATIONSHIP_REMOVE" ->
            gson.fromJson(data, ModelUserRelationship::class.java)
        "FRIEND_SUGGESTION_CREATE" -> gson.fromJson(data, FriendSuggestion::class.java)
        "FRIEND_SUGGESTION_DELETE" -> gson.fromJson(data, FriendSuggestionDelete::class.java)

        // Guilds
        "GUILD_CREATE", "GUILD_UPDATE", "GUILD_DELETE" -> gson.fromJson(data, Guild::class.java)
        "GUILD_BAN_ADD", "GUILD_BAN_REMOVE" -> gson.fromJson(data, ModelBan::class.java)
        "GUILD_EMOJIS_UPDATE" -> gson.fromJson(data, GuildEmojisUpdate::class.java)
        "GUILD_STICKERS_UPDATE" -> gson.fromJson(data, GuildStickersUpdate::class.java)
        "GUILD_INTEGRATIONS_UPDATE" -> gson.fromJson(data, ModelGuildIntegration.Update::class.java)
        "GUILD_APPLICATION_COMMANDS_UPDATE" -> gson.fromJson(data, GuildApplicationCommands::class.java)
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
        // REMOVE_EMOJI carries no user, so those members are absent on the wire for them.
        "MESSAGE_REACTION_ADD", "MESSAGE_REACTION_REMOVE",
        "MESSAGE_REACTION_REMOVE_ALL", "MESSAGE_REACTION_REMOVE_EMOJI" ->
            gson.fromJson(data, MessageReactionUpdate::class.java)

        // Presence and typing
        "PRESENCE_UPDATE" -> gson.fromJson(data, Presence::class.java)
        "PRESENCES_REPLACE" -> gson.fromJson<List<Presence>>(data, presenceListType)
        "TYPING_START" -> gson.fromJson(data, TypingUser::class.java)

        // Voice, calls and streams
        "VOICE_STATE_UPDATE" -> gson.fromJson(data, VoiceState::class.java)
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
