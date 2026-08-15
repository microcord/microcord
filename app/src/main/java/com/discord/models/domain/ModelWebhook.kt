package com.discord.models.domain

import com.discord.api.user.User
import com.discord.models.deserialization.gson.InboundGatewayGsonParser

/** A channel webhook as returned by `channels/{id}/webhooks`. */
class ModelWebhook : Model {
    var avatar: String? = null
        private set
    var name: String? = null
        private set
    var channelId: Long = 0
        private set
    var guildId: Long = 0
        private set
    var id: Long = 0
        private set
    var type: Int = TYPE_INCOMING
        private set

    /** Absent unless the caller can manage the webhook; a follower webhook never has one. */
    var token: String? = null
        private set
    var applicationId: Long? = null
        private set

    /** The creator, on `GET`; the followed channel's author on a follower webhook. */
    var user: User? = null
        private set
    var sourceGuild: WebhookSource? = null
        private set
    var sourceChannel: WebhookSource? = null
        private set

    override fun assignField(reader: Model.JsonReader) {
        when (reader.nextName()) {
            "avatar" -> avatar = reader.nextString(avatar)
            "name" -> name = reader.nextString(name)
            "channel_id" -> channelId = reader.nextLong(channelId)
            "guild_id" -> guildId = reader.nextLong(guildId)
            "id" -> id = reader.nextLong(id)
            "type" -> type = reader.nextInt(type)
            "token" -> token = reader.nextString(token)
            "application_id" -> applicationId = reader.nextLongOrNull()
            "user" -> user = InboundGatewayGsonParser.fromJson(reader, User::class.java)
            "source_guild" -> sourceGuild = reader.parse(WebhookSource())
            "source_channel" -> sourceChannel = reader.parse(WebhookSource())
            else -> reader.skipValue()
        }
    }

    override fun equals(other: Any?): Boolean = other is ModelWebhook &&
        avatar == other.avatar && name == other.name && channelId == other.channelId &&
        guildId == other.guildId && id == other.id && type == other.type && token == other.token &&
        applicationId == other.applicationId && user == other.user &&
        sourceGuild == other.sourceGuild && sourceChannel == other.sourceChannel

    override fun hashCode(): Int = listOf(
        avatar, name, channelId, guildId, id, type, token, applicationId, user, sourceGuild, sourceChannel,
    ).hashCode()

    override fun toString(): String = "ModelWebhook(avatar=$avatar, name=$name, " +
        "channelId=$channelId, guildId=$guildId, id=$id, type=$type)"

    /** The guild or channel a follower webhook republishes from. */
    class WebhookSource : Model {
        var id: Long = 0
            private set
        var name: String? = null
            private set
        var icon: String? = null
            private set

        override fun assignField(reader: Model.JsonReader) {
            when (reader.nextName()) {
                "id" -> id = reader.nextLong(id)
                "name" -> name = reader.nextString(name)
                "icon" -> icon = reader.nextString(icon)
                else -> reader.skipValue()
            }
        }

        override fun equals(other: Any?): Boolean =
            other is WebhookSource && id == other.id && name == other.name && icon == other.icon

        override fun hashCode(): Int = listOf(id, name, icon).hashCode()

        override fun toString(): String = "WebhookSource(id=$id, name=$name)"
    }

    companion object {
        const val TYPE_INCOMING = 1
        const val TYPE_CHANNEL_FOLLOWER = 2
    }
}
