package com.discord.models.domain

import com.discord.api.thread.MuteConfig
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@JsonAdapter(ModelMuteConfigTypeAdapter::class)
data class ModelMuteConfig(
    val endTimeMs: Long?,
    @SerializedName("end_time") val endTime: String?,
) {
    constructor(endTimeMs: Long?) : this(endTimeMs, endTimeMs?.let(::formatUtc))
    constructor(endTime: String?) : this(endTime?.let(::parseUtc), endTime)
    constructor(muteConfig: MuteConfig?) : this(muteConfig?.endTime)

    companion object {
        private val formats = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )

        private fun parseUtc(value: String): Long = formats.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.ROOT).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value)?.time
            }.getOrNull()
        } ?: 0L

        private fun formatUtc(value: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(value)
    }
}

class ModelMuteConfigTypeAdapter : TypeAdapter<ModelMuteConfig>() {
    override fun read(reader: JsonReader): ModelMuteConfig {
        var endTime: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "end_time") {
                endTime = if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    null
                } else {
                    reader.nextString()
                }
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return ModelMuteConfig(endTime)
    }

    override fun write(writer: JsonWriter, value: ModelMuteConfig?) {
        if (value == null) {
            writer.nullValue()
        } else {
            writer.beginObject().name("end_time").value(value.endTime).endObject()
        }
    }
}
