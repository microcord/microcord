package com.discord.nullserializable

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NullSerializableTypeAdapterTest {
    private open class Animal(val name: String)
    private class Dog(name: String, val bark: Boolean) : Animal(name)
    private data class Holder(val animal: NullSerializable<Animal>? = null)

    private val gson = GsonBuilder()
        .registerTypeAdapterFactory(NullSerializableTypeAdapterFactory())
        .create()

    @Test
    fun `distinguishes absent explicit null and runtime values`() {
        assertFalse(gson.toJson(Holder()).contains("animal"))
        assertEquals("{\"animal\":null}", gson.toJson(Holder(NullSerializable.Null())))
        assertTrue(gson.toJson(Holder(NullSerializable.Value(Dog("Ada", true)))).contains("\"bark\":true"))
        assertTrue(gson.fromJson("{\"animal\":null}", Holder::class.java).animal is NullSerializable.Null)
    }
}
