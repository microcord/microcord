package com.discord.nullserializable

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

internal class NullSerializableTypeAdapter<T>(
    private val gson: Gson,
    private val delegate: TypeAdapter<T>,
) : TypeAdapter<NullSerializable<T>>() {
    override fun read(reader: JsonReader): NullSerializable<T> =
        delegate.read(reader)?.let(NullSerializable<T>::Value) ?: NullSerializable.Null()

    override fun write(writer: JsonWriter, value: NullSerializable<T>?) {
        when (value) {
            is NullSerializable.Value -> {
                val runtimeValue = requireNotNull(value.value)
                @Suppress("UNCHECKED_CAST")
                (gson.getAdapter(runtimeValue.javaClass) as TypeAdapter<Any>).write(writer, runtimeValue)
            }
            is NullSerializable.Null -> writer.withSerializeNulls(true) { nullValue() }
            null -> writer.withSerializeNulls(false) { nullValue() }
        }
    }

    private inline fun JsonWriter.withSerializeNulls(enabled: Boolean, action: JsonWriter.() -> Unit) {
        val previous = serializeNulls
        serializeNulls = enabled
        try {
            action()
        } finally {
            serializeNulls = previous
        }
    }
}
