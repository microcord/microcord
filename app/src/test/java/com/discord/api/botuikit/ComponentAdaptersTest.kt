package com.discord.api.botuikit

import com.discord.api.botuikit.gson.ComponentRuntimeTypeAdapter
import com.discord.api.botuikit.gson.ComponentTypeTypeAdapter
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentAdaptersTest {
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerDiscordApiTypeAdapters()
        .create()

    @Test
    fun nestedComponentsUseTheirRuntimeTypes() {
        val row = gson.fromJson(
            """{"type":1,"components":[{"type":2,"custom_id":"go","label":"Go","style":1,"disabled":false}]}""",
            Component::class.java,
        )

        assertTrue(row is ActionRowComponent)
        val button = (row as ActionRowComponent).components.single() as ButtonComponent
        assertEquals(ComponentType.BUTTON, button.type)
        assertEquals(ButtonStyle.PRIMARY, button.style)
        assertEquals("go", button.customId)
    }

    @Test
    fun exactAdaptersPreserveUnknownComponentTypeZero() {
        assertEquals(ComponentType.UNKNOWN, gson.fromJson("99", ComponentType::class.java))
        assertEquals("0", gson.toJson(ComponentType.UNKNOWN, ComponentType::class.java))
        assertEquals(
            "com.discord.api.botuikit.gson.ComponentTypeTypeAdapter",
            ComponentTypeTypeAdapter::class.java.name,
        )
        assertEquals(
            "com.discord.api.botuikit.gson.ComponentRuntimeTypeAdapter",
            ComponentRuntimeTypeAdapter::class.java.name,
        )
    }
}
