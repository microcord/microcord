package com.discord.api.premium

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Reads [PriceTierType] from its integer wire value.
 *
 * A missing or unrecognised value resolves to [PriceTierType.UNKNOWN], which is written back out
 * as JSON null so an unknown tier is never echoed as a bogus id.
 */
class PriceTierTypeAdapter : TypeAdapter<PriceTierType>() {
    override fun read(reader: JsonReader): PriceTierType {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return PriceTierType.UNKNOWN
        }
        return if (reader.nextInt() == PriceTierType.GUILD_ROLE_SUBSCRIPTIONS.apiValue) {
            PriceTierType.GUILD_ROLE_SUBSCRIPTIONS
        } else {
            PriceTierType.UNKNOWN
        }
    }

    override fun write(writer: JsonWriter, value: PriceTierType?) {
        when (value) {
            null -> writer.nullValue()
            PriceTierType.UNKNOWN -> writer.nullValue()
            PriceTierType.GUILD_ROLE_SUBSCRIPTIONS -> writer.value(value.apiValue)
        }
    }
}
