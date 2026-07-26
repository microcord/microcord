package com.discord.api.friendsuggestions

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

/**
 * Reads [AllowedInSuggestionsType] from its integer wire value.
 *
 * An unrecognised value resolves to [AllowedInSuggestionsType.DISABLED] — the setting that shares
 * least — so a value this build does not understand never widens discoverability.
 */
class AllowedInSuggestionsTypeAdapter : TypeAdapter<AllowedInSuggestionsType>() {
    override fun read(reader: JsonReader): AllowedInSuggestionsType {
        val apiValue = reader.nextInt()
        return AllowedInSuggestionsType.entries.firstOrNull { it.apiValue == apiValue }
            ?: AllowedInSuggestionsType.DISABLED
    }

    override fun write(writer: JsonWriter, value: AllowedInSuggestionsType?) {
        if (value == null) writer.nullValue() else writer.value(value.apiValue)
    }
}
