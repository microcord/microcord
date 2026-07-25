package com.discord.models.domain

import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.internal.Streams
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

data class ModelTypingResponse(
    val messageSendCooldownMs: Long?,
    val threadCreateCooldownMs: Long?,
)

@JsonAdapter(ModelMessageDelete.Adapter::class)
data class ModelMessageDelete(
    val channelId: Long,
    val messageIds: List<Long>?,
) {
    constructor(channelId: Long, messageId: Long) : this(channelId, listOf(messageId))

    class Adapter : TypeAdapter<ModelMessageDelete>() {
        override fun read(reader: JsonReader): ModelMessageDelete {
            val json = Streams.parse(reader).asJsonObject
            val ids = when {
                json.has("ids") -> json.getAsJsonArray("ids").map { it.asLong }
                json.has("id") -> listOf(json["id"].asLong)
                else -> null
            }
            return ModelMessageDelete(json.get("channel_id")?.asLong ?: 0L, ids)
        }

        override fun write(writer: JsonWriter, value: ModelMessageDelete?) {
            if (value == null) {
                writer.nullValue()
                return
            }
            val json = com.google.gson.JsonObject().apply {
                addProperty("channel_id", value.channelId)
                add("ids", com.google.gson.JsonArray().also { ids -> value.messageIds.orEmpty().forEach(ids::add) })
            }
            Streams.write(json, writer)
        }
    }
}
