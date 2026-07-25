package com.discord.gateway.io

import com.discord.api.channel.Channel
import com.discord.api.channel.ChannelPinsUpdate
import com.discord.api.friendsuggestions.FriendSuggestion
import com.discord.api.friendsuggestions.FriendSuggestionDelete
import com.discord.api.guild.Guild
import com.discord.api.guildmember.GuildMember
import com.discord.api.guildmember.GuildMemberRemove
import com.discord.api.guildmember.GuildMembersChunk
import com.discord.api.message.Message
import com.discord.api.message.reaction.MessageReactionUpdate
import com.discord.api.message.reaction.MessageReactionRemoveAll
import com.discord.api.message.reaction.MessageReactionRemoveEmoji
import com.discord.api.presence.Presence
import com.discord.api.role.GuildRoleCreate
import com.discord.api.role.GuildRoleDelete
import com.discord.api.role.GuildRoleUpdate
import com.discord.api.stageinstance.StageInstance
import com.discord.api.user.User
import com.discord.api.user.TypingUser
import com.discord.gateway.opcodes.Opcode
import com.discord.models.domain.ModelChannelUnreadUpdate
import com.discord.models.domain.ModelGuildMemberListUpdate
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadState
import com.discord.models.domain.ModelSession
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.domain.ModelUserNote
import com.discord.models.domain.ModelUserSettings
import com.discord.models.thread.dto.ModelThreadListSync
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

class IncomingParser(private val gson: Gson) {
    fun parse(json: String): Incoming {
        val envelope = JsonParser.parseString(json).asJsonObject
        val type = envelope["t"]?.takeUnless(JsonElement::isJsonNull)?.asString
        val seq = envelope["s"]?.takeUnless(JsonElement::isJsonNull)?.asInt
        val opcode = Opcode.fromApiInt(envelope["op"]?.asInt ?: -1)
        val rawData = envelope["d"]
        val data = when {
            rawData == null || rawData.isJsonNull -> null
            opcode == Opcode.HELLO -> gson.fromJson(rawData, Hello::class.java)
            opcode == Opcode.INVALID_SESSION -> rawData.asBoolean
            opcode == Opcode.DISPATCH -> parseDispatch(type, rawData)
            else -> rawData.deepCopy()
        }
        return Incoming(type, seq, opcode, data)
    }

    private fun parseDispatch(type: String?, data: JsonElement): Any = when (type) {
        "READY" -> gson.fromJson(data, ModelPayload::class.java)
        "USER_SETTINGS_UPDATE" -> gson.fromJson(data, ModelUserSettings::class.java)
        "USER_GUILD_SETTINGS_UPDATE" -> gson.fromJson(data, ModelNotificationSettings::class.java)
        "USER_UPDATE" -> gson.fromJson(data, User::class.java)
        "GUILD_CREATE", "GUILD_UPDATE", "GUILD_DELETE" -> gson.fromJson(data, Guild::class.java)
        "GUILD_MEMBER_ADD", "GUILD_MEMBER_UPDATE" -> gson.fromJson(data, GuildMember::class.java)
        "GUILD_MEMBER_REMOVE" -> gson.fromJson(data, GuildMemberRemove::class.java)
        "GUILD_MEMBERS_CHUNK" -> gson.fromJson(data, GuildMembersChunk::class.java)
        "GUILD_ROLE_CREATE" -> gson.fromJson(data, GuildRoleCreate::class.java)
        "GUILD_ROLE_UPDATE" -> gson.fromJson(data, GuildRoleUpdate::class.java)
        "GUILD_ROLE_DELETE" -> gson.fromJson(data, GuildRoleDelete::class.java)
        "GUILD_MEMBER_LIST_UPDATE" -> gson.fromJson(data, ModelGuildMemberListUpdate::class.java)
        "CHANNEL_CREATE", "CHANNEL_UPDATE", "CHANNEL_DELETE",
        "THREAD_CREATE", "THREAD_UPDATE", "THREAD_DELETE" -> gson.fromJson(data, Channel::class.java)
        "THREAD_LIST_SYNC" -> gson.fromJson(data, ModelThreadListSync::class.java)
        "PRESENCE_UPDATE" -> gson.fromJson(data, Presence::class.java)
        "PRESENCES_REPLACE" -> gson.fromJson(data, object : TypeToken<List<Presence>>() {}.type)
        "SESSIONS_REPLACE" -> gson.fromJson(data, object : TypeToken<List<ModelSession>>() {}.type)
        "RELATIONSHIP_ADD", "RELATIONSHIP_REMOVE" -> gson.fromJson(data, ModelUserRelationship::class.java)
        "FRIEND_SUGGESTION_CREATE" -> gson.fromJson(data, FriendSuggestion::class.java)
        "FRIEND_SUGGESTION_DELETE" -> gson.fromJson(data, FriendSuggestionDelete::class.java)
        "USER_NOTE_UPDATE" -> gson.fromJson(data, ModelUserNote.Update::class.java)
        "MESSAGE_ACK" -> gson.fromJson(data, ModelReadState::class.java)
        "MESSAGE_CREATE", "MESSAGE_UPDATE" -> gson.fromJson(data, Message::class.java)
        "MESSAGE_DELETE", "MESSAGE_DELETE_BULK" -> gson.fromJson(data, MessageDeleteEvent::class.java)
        "MESSAGE_REACTION_ADD", "MESSAGE_REACTION_REMOVE" -> gson.fromJson(data, MessageReactionUpdate::class.java)
        "MESSAGE_REACTION_REMOVE_ALL" -> gson.fromJson(data, MessageReactionRemoveAll::class.java)
        "MESSAGE_REACTION_REMOVE_EMOJI" -> gson.fromJson(data, MessageReactionRemoveEmoji::class.java)
        "TYPING_START" -> gson.fromJson(data, TypingUser::class.java)
        "CHANNEL_PINS_UPDATE" -> gson.fromJson(data, ChannelPinsUpdate::class.java)
        "CHANNEL_UNREAD_UPDATE" -> gson.fromJson(data, ModelChannelUnreadUpdate::class.java)
        "STAGE_INSTANCE_CREATE", "STAGE_INSTANCE_UPDATE", "STAGE_INSTANCE_DELETE" ->
            gson.fromJson(data, StageInstance::class.java)
        else -> data.deepCopy()
    }
}
