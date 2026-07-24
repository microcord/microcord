package com.discord.api.utcdatetime

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class UtcDateTimeTypeAdapter : TypeAdapter<UtcDateTime>() {
    override fun read(reader: JsonReader): UtcDateTime? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        val millis = runCatching { formatter().parse(reader.nextString())?.time ?: 0 }.getOrDefault(0)
        return UtcDateTime(millis)
    }

    override fun write(writer: JsonWriter, value: UtcDateTime?) {
        if (value == null) writer.nullValue() else writer.value(formatter().format(Date(value.dateTimeMillis)))
    }

    private fun formatter() = SimpleDateFormat(FORMAT, Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private companion object {
        const val FORMAT = "yyyy-MM-dd'T'HH:mm:ss"
    }
}
