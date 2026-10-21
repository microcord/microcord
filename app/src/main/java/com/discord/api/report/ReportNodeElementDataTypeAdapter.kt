package com.discord.api.report

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/** A [ReportNodeElementData] travels as the two-element array `[elementKey, elementValue]`. */
class ReportNodeElementDataTypeAdapter : TypeAdapter<ReportNodeElementData>() {
    override fun read(reader: JsonReader): ReportNodeElementData {
        reader.beginArray()
        val elementKey = reader.nextString()
        val elementValue = reader.nextString()
        // Newer menus append extra columns, such as a description, after the key and value.
        while (reader.hasNext()) reader.skipValue()
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

/**
 * [ReportNodeElement.data], which is only a list of rows for the element types this client knows.
 *
 * Element types added since (free text, dropdowns, radio groups) carry an object, or rows of
 * objects, instead. Those read as null or are dropped, so the screen skips the element like any
 * other unknown type rather than failing the whole menu.
 */
class ReportNodeElementDataListTypeAdapter : TypeAdapter<List<ReportNodeElementData>?>() {
    private val row = ReportNodeElementDataTypeAdapter()

    override fun read(reader: JsonReader): List<ReportNodeElementData>? {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return null
        }
        val rows = ArrayList<ReportNodeElementData>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.BEGIN_ARRAY) rows += row.read(reader) else reader.skipValue()
        }
        reader.endArray()
        return rows
    }

    override fun write(writer: JsonWriter, value: List<ReportNodeElementData>?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginArray()
        value.forEach { row.write(writer, it) }
        writer.endArray()
    }
}
