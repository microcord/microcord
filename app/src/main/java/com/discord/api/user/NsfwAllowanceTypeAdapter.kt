package com.discord.api.user

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

class NsfwAllowanceTypeAdapter : TypeAdapter<NsfwAllowance>() {
    override fun read(reader: JsonReader): NsfwAllowance {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return NsfwAllowance.UNKNOWN
        }
        return if (reader.nextBoolean()) NsfwAllowance.ALLOWED else NsfwAllowance.DISALLOWED
    }

    override fun write(writer: JsonWriter, value: NsfwAllowance?) {
        when (value) {
            null, NsfwAllowance.UNKNOWN -> writer.nullValue()
            NsfwAllowance.ALLOWED -> writer.value(true)
            NsfwAllowance.DISALLOWED -> writer.value(false)
        }
    }
}
