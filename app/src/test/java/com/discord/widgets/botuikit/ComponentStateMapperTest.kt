package com.discord.widgets.botuikit

import com.discord.api.botuikit.*
import com.discord.models.botuikit.*
import com.discord.stores.StoreApplicationInteractions
import com.discord.widgets.botuikit.ComponentChatListState.ComponentStoreState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentStateMapperTest {
    private val allEnabled = object : ComponentExperiments {
        override fun isEnabled(componentType: ComponentType) = componentType != ComponentType.UNKNOWN
    }

    @Test
    fun mapsPreorderIndicesAndDisablesSiblingsWhileSending() {
        val components = listOf(
            ActionRowComponent(
                ComponentType.ACTION_ROW,
                listOf(button("button"), select("select")),
            ),
            button("outside"),
        )
        val state = ComponentStoreState(
            interactionState = mapOf(
                2 to StoreApplicationInteractions.InteractionSendState.Loading,
                3 to StoreApplicationInteractions.InteractionSendState.Failed("ignored while loading"),
            ),
            animateEmojis = false,
        )

        val mapped = ComponentStateMapper.processComponentsToMessageComponents(
            components,
            state,
            allEnabled,
            animateEmojis = true,
        )

        val row = mapped[0] as ActionRowMessageComponent
        assertEquals(0, row.index)
        assertEquals(listOf(1, 2), row.children.map { it.index })
        assertEquals(ActionInteractionComponentState.Disabled, (row.children[0] as ButtonMessageComponent).stateInteraction)
        assertEquals(ActionInteractionComponentState.Loading, (row.children[1] as SelectMessageComponent).stateInteraction)
        assertEquals(ActionInteractionComponentState.Disabled, (mapped[1] as ButtonMessageComponent).stateInteraction)
        assertFalse((row.children[0] as ButtonMessageComponent).emojiAnimationsEnabled)
    }

    @Test
    fun preservesIndicesWhenExperimentsFilterAComponent() {
        val selectOption = SelectItem("One", "1", null, null, false)
        val experiments = object : ComponentExperiments {
            override fun isEnabled(componentType: ComponentType) = componentType != ComponentType.BUTTON
        }

        val mapped = ComponentStateMapper.processComponentsToMessageComponents(
            listOf(
                ActionRowComponent(
                    ComponentType.ACTION_ROW,
                    listOf(button("hidden"), select("shown", listOf(selectOption))),
                ),
            ),
            componentStoreState = null,
            componentExperiments = experiments,
            animateEmojis = false,
        )

        val row = mapped.single() as ActionRowMessageComponent
        val select = row.children.single() as SelectMessageComponent
        assertEquals(2, select.index)
        assertTrue(select.selectedOptions.isEmpty())
        assertFalse(select.emojiAnimationsEnabled)
    }

    @Test
    fun mapsFailureWhenNoInteractionIsLoading() {
        val mapped = ComponentStateMapper.processComponentsToMessageComponents(
            listOf(button("failed")),
            ComponentStoreState(
                interactionState = mapOf(
                    0 to StoreApplicationInteractions.InteractionSendState.Failed("try again"),
                ),
            ),
            allEnabled,
            animateEmojis = true,
        )

        assertEquals(
            ActionInteractionComponentState.Failed("try again"),
            (mapped.single() as ButtonMessageComponent).stateInteraction,
        )
    }

    @Test
    fun mapsNestedComponentsV2AndSectionAccessoryInPreorder() {
        val mapped = ComponentStateMapper.processComponentsToMessageComponents(
            listOf(
                ContainerComponent(
                    type = ComponentType.CONTAINER,
                    id = 1,
                    components = listOf(
                        TextDisplayComponent(ComponentType.TEXT_DISPLAY, 2, "hello"),
                        SectionComponent(
                            ComponentType.SECTION,
                            3,
                            listOf(TextDisplayComponent(ComponentType.TEXT_DISPLAY, 4, "section")),
                            button("accessory"),
                        ),
                    ),
                    accentColor = 0x5865f2,
                    spoiler = false,
                ),
            ),
            componentStoreState = null,
            componentExperiments = allEnabled,
            animateEmojis = false,
        )

        val container = mapped.single() as ContainerMessageComponent
        val section = container.children[1] as SectionMessageComponent
        assertEquals(listOf(0, 1, 2), listOf(container.index, container.children[0].index, section.index))
        assertEquals(3, section.components.single().index)
        assertEquals(4, section.accessory.index)
        assertTrue(section.accessory is ButtonMessageComponent)
    }

    private fun button(customId: String) = ButtonComponent(
        ComponentType.BUTTON,
        customId,
        customId,
        ButtonStyle.PRIMARY,
        false,
        null,
        null,
    )

    private fun select(customId: String, options: List<SelectItem> = emptyList()) = SelectComponent(
        ComponentType.SELECT,
        customId,
        false,
        null,
        1,
        1,
        options,
    )
}
