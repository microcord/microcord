package com.discord.api.application

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/** A data-handling practice the user acknowledged when authorizing an application. */
enum class ApplicationDisclosureType(val apiValue: Int) {
    UNSPECIFIED(0),
    IP_LOCATION(1),
    DISPLAYS_ADVERTISEMENTS(2),
    PARTNER_SDK_DATA_SHARING_MESSAGE(3),
}

/** An unrecognised value reads as [ApplicationDisclosureType.UNSPECIFIED], which renders no row. */
class ApplicationDisclosureTypeAdapter : TypeAdapter<ApplicationDisclosureType>() {
    override fun read(reader: JsonReader): ApplicationDisclosureType {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return ApplicationDisclosureType.UNSPECIFIED
        }
        val apiValue = reader.nextInt()
        return ApplicationDisclosureType.entries.firstOrNull { it.apiValue == apiValue }
            ?: ApplicationDisclosureType.UNSPECIFIED
    }

    override fun write(writer: JsonWriter, value: ApplicationDisclosureType?) {
        if (value == null) writer.nullValue() else writer.value(value.apiValue)
    }
}
