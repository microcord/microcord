package com.discord.api.localizedstring

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Reads a [LocalizedString] from either shape the server uses.
 *
 * A bare JSON string is a string with no translations; an object carries `default` plus a
 * `localizations` map of locale to translation. Any other token is a protocol error and throws.
 */
class LocalizedStringTypeAdapter : TypeAdapter<LocalizedString>() {
    override fun read(reader: JsonReader): LocalizedString = when (val token = reader.peek()) {
        JsonToken.STRING -> LocalizedString(reader.nextString(), emptyMap())
        JsonToken.BEGIN_OBJECT -> readObject(reader)
        else -> throw IllegalArgumentException("could not parse localized string. token was: $token")
    }

    private fun readObject(reader: JsonReader): LocalizedString {
        reader.beginObject()
        var default = ""
        val localizations = HashMap<String, String>()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "default" -> default = reader.nextString()
                "localizations" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        localizations[reader.nextName()] = reader.nextString()
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return LocalizedString(default, localizations)
    }

    override fun write(writer: JsonWriter, value: LocalizedString?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name("default").value(value.default)
        writer.name("localizations")
        writer.beginObject()
        for ((locale, localization) in value.localizations) {
            writer.name(locale).value(localization)
        }
        writer.endObject()
        writer.endObject()
    }
}
