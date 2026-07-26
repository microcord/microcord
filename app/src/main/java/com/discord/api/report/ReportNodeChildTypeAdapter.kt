package com.discord.api.report

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

/** A [ReportNodeChild] travels as the two-element array `[name, ref]`. */
class ReportNodeChildTypeAdapter : TypeAdapter<ReportNodeChild>() {
    override fun read(reader: JsonReader): ReportNodeChild {
        reader.beginArray()
        val name = reader.nextString()
        val ref = reader.nextInt()
        reader.endArray()
        return ReportNodeChild(name, ref)
    }

    override fun write(writer: JsonWriter, value: ReportNodeChild) {
        writer.beginArray().value(value.name).value(value.ref).endArray()
    }
}
