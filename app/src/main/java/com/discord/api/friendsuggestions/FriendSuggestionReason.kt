package com.discord.api.friendsuggestions

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

data class FriendSuggestionReason(
    val type: FriendSuggestionReasonType,
    val platformType: String,
    val name: String?,
)

enum class FriendSuggestionReasonType(val apiValue: Int) {
    NONE(0),
    EXTERNAL_FRIEND(1),
}

class FriendSuggestionReasonTypeAdapter : TypeAdapter<FriendSuggestionReasonType>() {
    override fun read(reader: JsonReader): FriendSuggestionReasonType {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return FriendSuggestionReasonType.NONE
        }
        val apiValue = reader.nextInt()
        return FriendSuggestionReasonType.entries.firstOrNull { it.apiValue == apiValue }
            ?: FriendSuggestionReasonType.NONE
    }

    override fun write(writer: JsonWriter, value: FriendSuggestionReasonType?) {
        if (value == null) writer.nullValue() else writer.value(value.apiValue)
    }
}
