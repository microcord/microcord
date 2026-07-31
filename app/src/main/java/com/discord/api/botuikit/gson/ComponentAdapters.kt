package com.discord.api.botuikit.gson

import com.discord.api.botuikit.Component
import com.discord.api.botuikit.ComponentType
import com.discord.api.botuikit.UnknownComponent
import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.internal.Streams
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

class ComponentTypeTypeAdapter : TypeAdapter<ComponentType>() {
    override fun read(input: JsonReader): ComponentType {
        if (input.peek() == JsonToken.NULL) {
            input.nextNull()
            return ComponentType.UNKNOWN
        }
        val type = input.nextInt()
        return ComponentType.entries.firstOrNull { it.type == type } ?: ComponentType.UNKNOWN
    }

    override fun write(output: JsonWriter, value: ComponentType?) {
        if (value == null) output.nullValue() else output.value(value.type)
    }
}

object ComponentRuntimeTypeAdapter {
    val componentRuntimeTypeAdapterFactory: TypeAdapterFactory = object : TypeAdapterFactory {
        override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
            if (type.rawType != Component::class.java) return null
            @Suppress("UNCHECKED_CAST")
            return object : TypeAdapter<Component>() {
                override fun read(input: JsonReader): Component {
                    val json = Streams.parse(input)
                    if (!json.isJsonObject) return UnknownComponent()
                    val componentType = ComponentType.entries.firstOrNull {
                        it.type == json.asJsonObject.get("type")?.asInt
                    } ?: ComponentType.UNKNOWN
                    return if (componentType == ComponentType.UNKNOWN) UnknownComponent()
                    else gson.fromJson(json, componentType.clazz)
                }

                override fun write(output: JsonWriter, value: Component?) {
                    if (value == null) output.nullValue()
                    else Streams.write(gson.toJsonTree(value, value.javaClass), output)
                }
            } as TypeAdapter<T>
        }
    }
}
