package com.discord.models.botuikit

import com.discord.api.botuikit.ActionRowComponent
import com.discord.api.botuikit.ButtonComponent
import com.discord.api.botuikit.ButtonStyle
import com.discord.api.botuikit.ComponentEmoji
import com.discord.api.botuikit.ComponentType
import com.discord.api.botuikit.SelectComponent
import com.discord.api.botuikit.SelectItem
import com.discord.models.commands.ActionComponentStoreState
import com.discord.stores.StoreApplicationInteractions
import com.discord.widgets.botuikit.ComponentChatListState.ComponentStoreState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MessageComponentMergeTest {

    private val emoji = ComponentEmoji("1", "wave", true)
    private val first = SelectItem("One", "1", emoji, "First", true)
    private val second = SelectItem("Two", "2", null, "Second", false)

    private fun button(
        disabled: Boolean = false,
        url: String? = null,
    ) = ButtonComponent(
        type = ComponentType.BUTTON,
        customId = "button-id",
        label = "Press",
        style = ButtonStyle.PRIMARY,
        disabled = disabled,
        emoji = emoji,
        url = url,
    )

    private fun select(
        disabled: Boolean = false,
        minValues: Int? = 1,
        maxValues: Int? = 2,
    ) = SelectComponent(
        type = ComponentType.SELECT,
        customId = "select-id",
        disabled = disabled,
        placeholder = "Pick",
        minValues = minValues,
        maxValues = maxValues,
        options = listOf(first, second),
    )

    @Test
    fun `absent select bounds default to one rather than zero`() {
        val merged = select(minValues = null, maxValues = null)
            .mergeToMessageComponent(0, ActionInteractionComponentState.Enabled, ComponentStoreState())

        assertEquals(1, merged.minValues)
        assertEquals(1, merged.maxValues)
    }

    @Test
    fun `an explicit zero minimum is preserved`() {
        val merged = select(minValues = 0, maxValues = 3)
            .mergeToMessageComponent(0, ActionInteractionComponentState.Enabled, ComponentStoreState())

        assertEquals(0, merged.minValues)
        assertEquals(3, merged.maxValues)
    }

    @Test
    fun `a premium button cannot be interacted with`() {
        val merged = button().copy(style = ButtonStyle.PREMIUM, skuId = 42L)
            .mergeToMessageComponent(0, ActionInteractionComponentState.Enabled, ComponentStoreState())

        assertEquals(ActionInteractionComponentState.Disabled, merged.stateInteraction)
        assertEquals(42L, merged.skuId)
    }

    @Test
    fun `action row merge carries index and children`() {
        val children = listOf(
            button().mergeToMessageComponent(0, ActionInteractionComponentState.Enabled, ComponentStoreState()),
        )
        val row = ActionRowComponent(ComponentType.ACTION_ROW, listOf(button()))
            .mergeToMessageComponent(7, children)

        assertEquals(ComponentType.ACTION_ROW, row.type)
        assertEquals(7, row.index)
        assertEquals(children, row.components)
        assertEquals(children, row.children)
    }

    @Test
    fun `button merge copies every field and honours animate emojis`() {
        val merged = button().mergeToMessageComponent(
            index = 2,
            buttonStateInteraction = ActionInteractionComponentState.Loading,
            componentStoreState = ComponentStoreState(animateEmojis = false),
        )

        assertEquals(ComponentType.BUTTON, merged.type)
        assertEquals(2, merged.index)
        assertEquals("button-id", merged.customId)
        assertEquals("Press", merged.label)
        assertEquals(ButtonStyle.PRIMARY, merged.style)
        assertEquals(emoji, merged.emoji)
        assertEquals(null, merged.url)
        assertEquals(false, merged.emojiAnimationsEnabled)
        assertSame(ActionInteractionComponentState.Loading, merged.stateInteraction)
    }

    /** A disabled button outranks whatever the interaction store derived for its index. */
    @Test
    fun `disabled button is always disabled`() {
        val merged = button(disabled = true).mergeToMessageComponent(
            0,
            ActionInteractionComponentState.Loading,
            ComponentStoreState(),
        )
        assertSame(ActionInteractionComponentState.Disabled, merged.stateInteraction)
    }

    /** A link button never produces an interaction, so it can never be loading or failed. */
    @Test
    fun `link button is always enabled`() {
        val merged = button(url = "https://discord.com").mergeToMessageComponent(
            0,
            ActionInteractionComponentState.Failed("nope"),
            ComponentStoreState(),
        )
        assertSame(ActionInteractionComponentState.Enabled, merged.stateInteraction)
    }

    /** `disabled` is checked before `url`, so a disabled link button reads as disabled. */
    @Test
    fun `disabled link button is disabled not enabled`() {
        val merged = button(disabled = true, url = "https://discord.com").mergeToMessageComponent(
            0,
            ActionInteractionComponentState.Enabled,
            ComponentStoreState(),
        )
        assertSame(ActionInteractionComponentState.Disabled, merged.stateInteraction)
    }

    @Test
    fun `select merge copies every field and prefers the stored selection`() {
        val merged = select().mergeToMessageComponent(
            index = 5,
            actionInteractionComponentState = ActionInteractionComponentState.Enabled,
            componentStoreState = ComponentStoreState(
                selections = mapOf(5 to listOf(second)),
                animateEmojis = true,
            ),
        )

        assertEquals(ComponentType.SELECT, merged.type)
        assertEquals(5, merged.index)
        assertEquals("select-id", merged.customId)
        assertEquals("Pick", merged.placeholder)
        assertEquals(1, merged.minValues)
        assertEquals(2, merged.maxValues)
        assertEquals(listOf(first, second), merged.options)
        assertEquals(listOf(second), merged.selectedOptions)
        assertEquals(true, merged.emojiAnimationsEnabled)
    }

    /** With no stored selection the application's own `default` options are pre-selected. */
    @Test
    fun `select merge falls back to default options`() {
        val merged = select().mergeToMessageComponent(
            5,
            ActionInteractionComponentState.Enabled,
            ComponentStoreState(selections = mapOf(4 to listOf(second))),
        )
        assertEquals(listOf(first), merged.selectedOptions)
    }

    /** An empty stored selection is a real selection: it must not fall back to the defaults. */
    @Test
    fun `select merge keeps an explicitly emptied selection`() {
        val merged = select().mergeToMessageComponent(
            5,
            ActionInteractionComponentState.Enabled,
            ComponentStoreState(selections = mapOf(5 to emptyList<SelectItem>())),
        )
        assertEquals(emptyList<SelectItem>(), merged.selectedOptions)
    }

    @Test
    fun `disabled select is always disabled`() {
        val merged = select(disabled = true).mergeToMessageComponent(
            0,
            ActionInteractionComponentState.Failed("nope"),
            ComponentStoreState(),
        )
        assertSame(ActionInteractionComponentState.Disabled, merged.stateInteraction)
    }

    @Test
    fun `component store state defaults match the reference`() {
        val state = ComponentStoreState()
        assertEquals(emptyMap<Int, StoreApplicationInteractions.InteractionSendState>(), state.interactionState)
        assertEquals(emptyMap<Int, List<SelectItem>>(), state.selections)
        assertEquals(true, state.animateEmojis)
    }

    @Test
    fun `action component store state preserves its three fields`() {
        val failed = StoreApplicationInteractions.InteractionSendState.Failed("boom")
        val state = ActionComponentStoreState(loading = true, failed = failed, disabled = false)

        assertEquals(true, state.loading)
        assertEquals(failed, state.failed)
        assertEquals(false, state.disabled)
        assertEquals(state, state.copy())
    }
}
