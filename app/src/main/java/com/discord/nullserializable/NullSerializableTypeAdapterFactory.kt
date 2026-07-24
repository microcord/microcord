package com.discord.nullserializable

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.lang.reflect.ParameterizedType

class NullSerializableTypeAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (!NullSerializable::class.java.isAssignableFrom(type.rawType)) return null
        val valueType = (type.type as ParameterizedType).actualTypeArguments[0]
        @Suppress("UNCHECKED_CAST")
        val delegate = gson.getAdapter(TypeToken.get(valueType) as TypeToken<Any>)
        @Suppress("UNCHECKED_CAST")
        return Adapter(delegate) as TypeAdapter<T>
    }

    private class Adapter<T>(private val delegate: TypeAdapter<T>) : TypeAdapter<NullSerializable<T>>() {
        override fun read(reader: JsonReader): NullSerializable<T> {
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                return NullSerializable.Null()
            }
            return NullSerializable.Value(delegate.read(reader))
        }

        override fun write(writer: JsonWriter, value: NullSerializable<T>?) {
            when (value) {
                null -> writer.nullValue()
                is NullSerializable.Null -> writer.nullValue()
                is NullSerializable.Value -> delegate.write(writer, value.value)
            }
        }
    }
}
