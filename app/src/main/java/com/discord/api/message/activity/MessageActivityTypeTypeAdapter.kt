package com.discord.api.message.activity

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/** Reads [MessageActivityType] from its integer wire value. */
class MessageActivityTypeTypeAdapter : TypeAdapter<MessageActivityType>() {
    override fun read(reader: JsonReader): MessageActivityType {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return MessageActivityType.fromApiInt(null)
        }
        return MessageActivityType.fromApiInt(reader.nextInt())
    }

    override fun write(writer: JsonWriter, value: MessageActivityType?) {
        if (value == null) writer.nullValue() else writer.value(value.apiInt)
    }
}
