package com.discord.api.activity

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

class StatusDisplayTypeTypeAdapter : TypeAdapter<StatusDisplayType?>() {
    override fun read(reader: JsonReader): StatusDisplayType? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        val value = reader.nextInt()
        return StatusDisplayType.entries.firstOrNull { it.apiInt == value }
    }

    override fun write(writer: JsonWriter, value: StatusDisplayType?) {
        if (value == null) writer.nullValue() else writer.value(value.apiInt)
    }
}
