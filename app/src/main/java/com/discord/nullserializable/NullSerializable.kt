package com.discord.nullserializable

import java.io.Serializable

sealed class NullSerializable<out T> : Serializable {
    abstract val value: T?

    data class Null<T>(override val value: T? = null) : NullSerializable<T>()
    data class Value<T>(override val value: T) : NullSerializable<T>()
}
