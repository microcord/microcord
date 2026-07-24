package com.discord.api.stageinstance

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

class StageInstancePrivacyLevelTypeAdapter : TypeAdapter<StageInstancePrivacyLevel>() {
    override fun read(reader: JsonReader): StageInstancePrivacyLevel {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return StageInstancePrivacyLevel.INVALID
        }
        val apiValue = reader.nextInt()
        return StageInstancePrivacyLevel.entries.firstOrNull { it.apiValue == apiValue }
            ?: StageInstancePrivacyLevel.INVALID
    }

    override fun write(writer: JsonWriter, value: StageInstancePrivacyLevel?) {
        if (value == null) writer.nullValue() else writer.value(value.apiValue)
    }
}
