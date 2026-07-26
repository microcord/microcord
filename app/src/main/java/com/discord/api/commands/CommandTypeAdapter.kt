package com.discord.api.commands

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

/** Reads [ApplicationCommandType] from its integer wire value, unknown values folding to `UNKNOWN`. */
class CommandTypeAdapter : TypeAdapter<ApplicationCommandType>() {
    override fun read(reader: JsonReader): ApplicationCommandType =
        ApplicationCommandType.from(reader.nextInt())

    override fun write(writer: JsonWriter, value: ApplicationCommandType?) {
        if (value == null) writer.nullValue() else writer.value(value.type)
    }
}
