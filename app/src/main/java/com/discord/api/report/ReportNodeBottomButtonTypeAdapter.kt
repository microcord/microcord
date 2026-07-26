package com.discord.api.report

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Reads the `{ "type": …, "target": … }` object into a [ReportNodeBottomButton].
 *
 * `target` is only meaningful for `next` and arrives as either a number or a numeric string, so
 * both are accepted. An unrecognised `type` yields `null`, which renders as "no bottom button"
 * rather than a button that does nothing.
 */
class ReportNodeBottomButtonTypeAdapter : TypeAdapter<ReportNodeBottomButton>() {
    override fun read(reader: JsonReader): ReportNodeBottomButton? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        reader.beginObject()
        var type = ""
        var target: ReportNodeRef? = null
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "type" -> type = reader.nextString()
                "target" -> target = when (reader.peek()) {
                    JsonToken.NUMBER -> reader.nextInt()
                    JsonToken.STRING -> reader.nextString().toIntOrNull()
                    else -> {
                        reader.skipValue()
                        null
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return when (type) {
            "cancel" -> ReportNodeBottomButton.Cancel
            "submit" -> ReportNodeBottomButton.Submit
            "done" -> ReportNodeBottomButton.Done
            "next" -> target?.let(ReportNodeBottomButton::Next)
            else -> null
        }
    }

    override fun write(writer: JsonWriter, value: ReportNodeBottomButton?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name("type").value(value.type)
        writer.name("target")
        if (value is ReportNodeBottomButton.Next) writer.value(value.target) else writer.nullValue()
        writer.endObject()
    }
}
