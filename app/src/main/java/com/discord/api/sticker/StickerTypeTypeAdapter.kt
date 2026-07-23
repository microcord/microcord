package com.discord.api.sticker

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

class StickerTypeTypeAdapter : TypeAdapter<StickerType>() {
    override fun read(reader: JsonReader): StickerType {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return StickerType.UNKNOWN
        }
        return StickerType.fromApiValue(reader.nextInt())
    }

    override fun write(writer: JsonWriter, value: StickerType?) {
        if (value == null) writer.nullValue() else writer.value(value.apiValue)
    }
}
