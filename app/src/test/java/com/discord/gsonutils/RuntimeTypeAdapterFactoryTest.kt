package com.discord.gsonutils

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTypeAdapterFactoryTest {
    private interface Shape
    private data class Circle(val radius: Int) : Shape
    private data class Unknown(val type: String? = null, val value: Int = 0) : Shape

    @Test
    fun `reads writes and falls back to the registered default subtype`() {
        val gson = GsonBuilder().registerTypeAdapterFactory(
            RuntimeTypeAdapterFactory(Shape::class.java, "type", false, Unknown::class.java)
                .registerSubtype(Circle::class.java, "circle"),
        ).create()

        assertEquals(Circle(3), gson.fromJson("{\"type\":\"circle\",\"radius\":3}", Shape::class.java))
        assertEquals("{\"type\":\"circle\",\"radius\":3}", gson.toJson(Circle(3), Shape::class.java))
        assertTrue(gson.fromJson("{\"type\":\"future\",\"value\":7}", Shape::class.java) is Unknown)
    }
}
