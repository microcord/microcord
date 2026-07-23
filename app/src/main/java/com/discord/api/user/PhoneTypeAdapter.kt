package com.discord.api.user

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

class PhoneTypeAdapter : TypeAdapter<Phone>() {
    override fun read(reader: JsonReader): Phone {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return Phone.NoPhoneNumber
        }
        return Phone.PhoneNumber(reader.nextString())
    }

    override fun write(writer: JsonWriter, value: Phone?) {
        when (value) {
            null, Phone.NoPhoneNumber -> writer.nullValue()
            is Phone.PhoneNumber -> writer.value(value.number)
        }
    }
}
