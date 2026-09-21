package com.discord.api.botuikit

import com.discord.api.botuikit.gson.ComponentRuntimeTypeAdapter
import com.discord.api.botuikit.gson.ComponentTypeTypeAdapter
import com.discord.api.channel.Channel
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.MessageEmbed
import com.discord.models.botuikit.ActionInteractionComponentState
import com.discord.models.botuikit.ButtonMessageComponent
import com.discord.models.botuikit.ContainerMessageComponent
import com.discord.models.botuikit.SectionMessageComponent
import com.discord.models.botuikit.TextDisplayMessageComponent
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.message.Message
import com.discord.restapi.RestAPIParams
import com.discord.widgets.chat.list.entries.BotUiComponentEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
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

    @Test
    fun componentEmbedDeserializesAndUsesTheReadOnlyComponentRenderer() {
        val embed = gson.fromJson(
            """
            {
              "type": "components",
              "url": "https://example.invalid/download",
              "flags": 1,
              "components": [{
                "type": 17,
                "accent_color": 16711680,
                "spoiler": false,
                "components": [
                  {"type": 10, "content": "## [Download](https://example.invalid/download)\nChoose a platform."},
                  {"type": 14, "divider": true, "spacing": 1},
                  {
                    "type": 9,
                    "components": [{"type": 10, "content": "Windows"}],
                    "accessory": {
                      "type": 2,
                      "style": 5,
                      "url": "https://example.invalid/installer.exe",
                      "label": "Download",
                      "emoji": {"id": "1", "name": "Windows"}
                    }
                  }
                ]
              }]
            }
            """.trimIndent(),
            MessageEmbed::class.java,
        )
        val channel = gson.fromJson(
            """{"id":"2","guild_id":"3","type":0}""",
            Channel::class.java,
        )

        val entry = ChatListEntry.createEmbedEntries(
            message = Message(id = 1, channelId = channel.id, embeds = listOf(embed)),
            messageState = null,
            blockedChunkExpanded = false,
            allowAnimatedEmojis = true,
            autoPlayGifs = true,
            renderEmbeds = true,
            isThreadStarterMessage = false,
            channel = channel,
            author = null,
            roles = null,
            nickOrUsernames = emptyMap(),
        ).single() as BotUiComponentEntry

        assertEquals(EmbedType.COMPONENTS, embed.type)
        assertEquals(1, embed.flags)
        assertEquals(0, entry.sourceEmbedIndex)
        val container = entry.messageComponents.single() as ContainerMessageComponent
        assertEquals(0xff0000, container.accentColor)
        assertTrue(container.children.first() is TextDisplayMessageComponent)
        assertEquals(
            "## [Download](https://example.invalid/download)\nChoose a platform.",
            (container.children.first() as TextDisplayMessageComponent).content,
        )
        val button = (container.children.last() as SectionMessageComponent).accessory as ButtonMessageComponent
        assertEquals("https://example.invalid/installer.exe", button.url)
        assertEquals(ActionInteractionComponentState.Enabled, button.stateInteraction)
    }
}
