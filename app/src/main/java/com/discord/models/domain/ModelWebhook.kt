package com.discord.models.domain

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

    override fun assignField(reader: Model.JsonReader) {
        when (reader.nextName()) {
            "avatar" -> avatar = reader.nextString(avatar)
            "name" -> name = reader.nextString(name)
            "channel_id" -> channelId = reader.nextLong(channelId)
            "guild_id" -> guildId = reader.nextLong(guildId)
            "id" -> id = reader.nextLong(id)
            else -> reader.skipValue()
        }
    }

    override fun equals(other: Any?): Boolean = other is ModelWebhook &&
        avatar == other.avatar && name == other.name && channelId == other.channelId &&
        guildId == other.guildId && id == other.id

    override fun hashCode(): Int = listOf(avatar, name, channelId, guildId, id).hashCode()

    override fun toString(): String = "ModelWebhook(avatar=$avatar, name=$name, " +
        "channelId=$channelId, guildId=$guildId, id=$id)"
}
