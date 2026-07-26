package com.discord.api.message.embed

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

/**
 * Maps the wire string of an embed's `type` onto [EmbedType].
 *
 * An unrecognised value resolves to [EmbedType.UNKNOWN] — never `null` — so a new server-side
 * embed kind degrades to the generic renderer instead of blanking the message.
 */
class EmbedTypeTypeAdapter : TypeAdapter<EmbedType>() {
    override fun read(reader: JsonReader): EmbedType {
        val apiValue = reader.nextString()
        return EMBED_TYPES.firstOrNull { it.apiValue == apiValue } ?: EmbedType.UNKNOWN
    }

    override fun write(writer: JsonWriter, value: EmbedType?) {
        if (value == null) writer.nullValue() else writer.value(value.apiValue)
    }
}
