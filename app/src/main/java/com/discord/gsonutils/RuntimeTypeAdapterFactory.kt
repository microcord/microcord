package com.discord.gsonutils

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.internal.Streams
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

/** Gson adapter factory for first-party polymorphic payloads carrying a discriminator field. */
class RuntimeTypeAdapterFactory<T : Any>(
    private val baseType: Class<T>,
    private val typeFieldName: String,
    private val maintainType: Boolean,
    private val defaultType: Class<out T>? = null,
) : TypeAdapterFactory {
    private val labelToSubtype = LinkedHashMap<String, Class<out T>>()
    private val subtypeToLabel = LinkedHashMap<Class<out T>, String>()

    fun registerSubtype(subtype: Class<out T>, label: String): RuntimeTypeAdapterFactory<T> = apply {
        require(label !in labelToSubtype && subtype !in subtypeToLabel) {
            "Subtype or label is already registered"
        }
        labelToSubtype[label] = subtype
        subtypeToLabel[subtype] = label
    }

    override fun <R> create(gson: Gson, type: TypeToken<R>): TypeAdapter<R>? {
        if (type.rawType != baseType) return null

        val labelToDelegate = labelToSubtype.mapValues { (_, subtype) ->
            gson.getDelegateAdapter(this, TypeToken.get(subtype))
        }
        val subtypeToDelegate = subtypeToLabel.keys.associateWith { subtype ->
            gson.getDelegateAdapter(this, TypeToken.get(subtype))
        }
        val defaultDelegate = defaultType?.let { gson.getDelegateAdapter(this, TypeToken.get(it)) }

        val adapter = object : TypeAdapter<T>() {
            override fun read(reader: JsonReader): T {
                val element = Streams.parse(reader)
                val objectValue = element as? JsonObject
                    ?: throw JsonParseException("cannot deserialize $baseType because it is not an object")
                val labelElement = (if (maintainType) objectValue.get(typeFieldName) else objectValue.remove(typeFieldName))
                    ?: throw JsonParseException("cannot deserialize $baseType because it does not define a field named $typeFieldName")
                val label = labelElement.asString
                val delegate = labelToDelegate[label] ?: defaultDelegate
                    ?: throw JsonParseException("cannot deserialize $baseType subtype named $label; did you forget to register a subtype?")
                @Suppress("UNCHECKED_CAST")
                return delegate.fromJsonTree(objectValue) as T
            }

            override fun write(writer: JsonWriter, value: T) {
                val subtype = value.javaClass
                @Suppress("UNCHECKED_CAST")
                val delegate = subtypeToDelegate[subtype] as? TypeAdapter<T>
                    ?: throw JsonParseException("cannot serialize $subtype; did you forget to register a subtype?")
                val objectValue = delegate.toJsonTree(value).asJsonObject
                if (maintainType) {
                    Streams.write(objectValue, writer)
                    return
                }
                if (objectValue.has(typeFieldName)) {
                    throw JsonParseException("cannot serialize $subtype because it already defines a field named $typeFieldName")
                }
                val result = JsonObject().apply {
                    addProperty(typeFieldName, subtypeToLabel.getValue(subtype))
                    objectValue.entrySet().forEach { (name, element) -> add(name, element) }
                }
                Streams.write(result, writer)
            }
        }.nullSafe()

        @Suppress("UNCHECKED_CAST")
        return adapter as TypeAdapter<R>
    }
}
