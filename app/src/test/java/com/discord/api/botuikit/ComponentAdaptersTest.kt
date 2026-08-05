package com.discord.api.botuikit

import com.discord.api.botuikit.gson.ComponentRuntimeTypeAdapter
import com.discord.api.botuikit.gson.ComponentTypeTypeAdapter
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.restapi.RestAPIParams
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

    @Test
    fun currentModalComponentsDeserializeAndSubmitWithTypedValues() {
        val label = gson.fromJson(
            """{"type":18,"id":7,"label":"Terms","description":"Required","component":{"type":23,"id":8,"custom_id":"terms","default":true}}""",
            Component::class.java,
        ) as LabelComponent

        assertEquals(ComponentType.CHECKBOX, label.component.type)
        assertTrue((label.component as CheckboxComponent).default == true)

        val submitted = RestAPIParams.ModalInteractionData(
            id = 1,
            customId = "modal",
            components = listOf(
                RestAPIParams.ModalInteractionDataLayout(
                    ComponentType.LABEL,
                    7,
                    component = RestAPIParams.ModalInteractionDataBoolean(
                        ComponentType.CHECKBOX,
                        8,
                        "terms",
                        true,
                    ),
                ),
            ),
        )
        val json = gson.toJson(submitted)
        assertTrue(json.contains("\"component\":{\"type\":23,\"id\":8,\"custom_id\":\"terms\",\"value\":true}"))
    }

    @Test
    fun componentsV2PayloadUsesConcreteNestedTypes() {
        val container = gson.fromJson(
            """{"type":17,"id":1,"accent_color":5793266,"spoiler":false,"components":[{"type":10,"id":2,"content":"hello"},{"type":12,"id":3,"items":[{"media":{"url":"https://cdn.example/image.png"},"description":"preview","spoiler":true}]}]}""",
            Component::class.java,
        ) as ContainerComponent

        assertEquals(2, container.components.size)
        assertTrue(container.components[0] is TextDisplayComponent)
        assertTrue((container.components[0] as LayoutComponent).components.isEmpty())
        assertTrue(container.components[1] is MediaGalleryComponent)
        assertEquals("preview", (container.components[1] as MediaGalleryComponent).items.single().description)
    }
}
