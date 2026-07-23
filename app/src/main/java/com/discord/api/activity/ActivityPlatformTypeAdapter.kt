package com.discord.api.activity

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.util.Locale

class ActivityPlatformTypeAdapter : TypeAdapter<ActivityPlatform>() {
    override fun read(reader: JsonReader): ActivityPlatform {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return ActivityPlatform.UNKNOWN
        }
        val value = reader.nextString()
        return ActivityPlatform.entries.firstOrNull {
            it != ActivityPlatform.UNKNOWN && it.name.equals(value, ignoreCase = true)
        } ?: ActivityPlatform.UNKNOWN
    }

    override fun write(writer: JsonWriter, value: ActivityPlatform?) {
        if (value == null) writer.nullValue()
        else writer.value(value.name.lowercase(Locale.ROOT))
    }
}
