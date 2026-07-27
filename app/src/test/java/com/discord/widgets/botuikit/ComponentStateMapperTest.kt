package com.discord.widgets.botuikit

import com.discord.api.botuikit.ActionRowComponent
import com.discord.api.botuikit.ButtonComponent
import com.discord.api.botuikit.ButtonStyle
import com.discord.api.botuikit.ComponentType
import com.discord.api.botuikit.SelectComponent
import com.discord.api.botuikit.SelectItem
import com.discord.models.botuikit.ActionInteractionComponentState
import com.discord.models.botuikit.ActionRowMessageComponent
import com.discord.models.botuikit.ButtonMessageComponent
import com.discord.models.botuikit.SelectMessageComponent
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
