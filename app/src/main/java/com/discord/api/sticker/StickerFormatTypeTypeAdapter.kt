package com.discord.api.sticker

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

class StickerFormatTypeTypeAdapter : TypeAdapter<StickerFormatType>() {
    override fun read(reader: JsonReader): StickerFormatType {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return StickerFormatType.UNKNOWN
        }
        return StickerFormatType.fromApiValue(reader.nextInt())
    }

    override fun write(writer: JsonWriter, value: StickerFormatType?) {
        if (value == null) writer.nullValue() else writer.value(value.apiValue)
    }
}
