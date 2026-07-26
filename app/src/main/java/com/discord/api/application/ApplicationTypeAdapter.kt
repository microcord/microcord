package com.discord.api.application

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Reads [ApplicationType] from its integer wire value.
 *
 * The field is nullable on the wire, and both `null` and an unrecognised value resolve to
 * [ApplicationType.UNKNOWN], which is written back out as JSON null.
 */
class ApplicationTypeAdapter : TypeAdapter<ApplicationType>() {
    override fun read(reader: JsonReader): ApplicationType {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return ApplicationType.UNKNOWN
        }
        val apiValue = reader.nextInt()
        return ApplicationType.entries.firstOrNull { it != ApplicationType.UNKNOWN && it.apiValue == apiValue }
            ?: ApplicationType.UNKNOWN
    }

    override fun write(writer: JsonWriter, value: ApplicationType?) {
        if (value == null || value == ApplicationType.UNKNOWN) {
            writer.nullValue()
        } else {
            writer.value(value.apiValue)
        }
    }
}
