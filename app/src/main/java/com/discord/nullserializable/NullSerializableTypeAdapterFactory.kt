package com.discord.nullserializable

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import java.lang.reflect.ParameterizedType

class NullSerializableTypeAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (!NullSerializable::class.java.isAssignableFrom(type.rawType)) return null
        val valueType = (type.type as ParameterizedType).actualTypeArguments[0]
        @Suppress("UNCHECKED_CAST")
        val delegate = gson.getAdapter(TypeToken.get(valueType) as TypeToken<Any>)
        @Suppress("UNCHECKED_CAST")
        return NullSerializableTypeAdapter(gson, delegate) as TypeAdapter<T>
    }
}
