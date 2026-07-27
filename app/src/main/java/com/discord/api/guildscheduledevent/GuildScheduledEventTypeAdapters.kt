package com.discord.api.guildscheduledevent

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

class GuildScheduledEventStatusTypeAdapter : TypeAdapter<GuildScheduledEventStatus>() {
    override fun read(reader: JsonReader): GuildScheduledEventStatus {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return GuildScheduledEventStatus.UNKNOWN
        }
        // Read once. Inside the predicate this consumed a token per enum constant, so anything
        // but the first constant's value ran off the end of the value and into the next name.
        val apiValue = reader.nextInt()
        return GuildScheduledEventStatus.entries.firstOrNull { it.apiValue == apiValue }
            ?: GuildScheduledEventStatus.UNKNOWN
    }

    override fun write(writer: JsonWriter, value: GuildScheduledEventStatus?) {
        if (value == null) writer.nullValue() else writer.value(value.apiValue)
    }
}

class GuildScheduledEventEntityTypeTypeAdapter : TypeAdapter<GuildScheduledEventEntityType>() {
    override fun read(reader: JsonReader): GuildScheduledEventEntityType {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return GuildScheduledEventEntityType.UNKNOWN
        }
        // Read once; see [GuildScheduledEventStatusTypeAdapter].
        val apiValue = reader.nextInt()
        return GuildScheduledEventEntityType.entries.firstOrNull { it.apiValue == apiValue }
            ?: GuildScheduledEventEntityType.UNKNOWN
    }

    override fun write(writer: JsonWriter, value: GuildScheduledEventEntityType?) {
        if (value == null) writer.nullValue() else writer.value(value.apiValue)
    }
}
