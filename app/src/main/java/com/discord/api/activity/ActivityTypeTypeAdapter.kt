package com.discord.api.activity

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

class ActivityTypeTypeAdapter : TypeAdapter<ActivityType>() {
    override fun read(reader: JsonReader): ActivityType {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return ActivityType.UNKNOWN
        }
        val value = reader.nextInt()
        return ActivityType.entries.firstOrNull { it.apiInt == value } ?: ActivityType.UNKNOWN
    }

    override fun write(writer: JsonWriter, value: ActivityType?) {
        if (value == null) writer.nullValue() else writer.value(value.apiInt)
    }
}
