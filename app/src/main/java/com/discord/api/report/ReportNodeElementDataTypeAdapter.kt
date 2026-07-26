package com.discord.api.report

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

/** A [ReportNodeElementData] travels as the two-element array `[elementKey, elementValue]`. */
class ReportNodeElementDataTypeAdapter : TypeAdapter<ReportNodeElementData>() {
    override fun read(reader: JsonReader): ReportNodeElementData {
        reader.beginArray()
        val elementKey = reader.nextString()
        val elementValue = reader.nextString()
        reader.endArray()
        return ReportNodeElementData(elementKey, elementValue)
    }

    override fun write(writer: JsonWriter, value: ReportNodeElementData?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginArray().value(value.elementKey).value(value.elementValue).endArray()
    }
}
