package com.discord.widgets.botuikit

import com.discord.api.botuikit.ButtonStyle
import com.discord.api.botuikit.ComponentEmoji
import com.discord.api.botuikit.ComponentType
import com.discord.api.botuikit.SelectItem
import com.discord.models.botuikit.ActionInteractionComponentState
import com.discord.models.botuikit.ActionRowMessageComponent
import com.discord.models.botuikit.ButtonMessageComponent
import com.discord.models.botuikit.SelectMessageComponent
import com.discord.models.botuikit.childError
import com.discord.stores.StoreApplicationInteractions
import com.discord.widgets.botuikit.views.buttonLabelPaddingDp
import com.discord.widgets.botuikit.views.hasEmoji
import com.discord.widgets.botuikit.views.hasIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotUiKitContractsTest {
    @Test
    fun componentChatStateCombinesEveryMessageId() {
        val selection = SelectItem("One", "1", null, null, true)
        val failed = StoreApplicationInteractions.InteractionSendState.Failed("nope")

        val state = ComponentChatListState.combineState(
            interactions = mapOf(1L to mapOf(2 to failed)),
            selections = mapOf(2L to mapOf(3 to listOf(selection))),
            animateEmojis = false,
        )

        assertEquals(setOf(1L, 2L), state.keys)
        assertEquals(failed, state[1L]?.interactionState?.get(2))
        assertEquals(selection, state[2L]?.selections?.get(3)?.single())
        assertTrue(state.values.none { it.animateEmojis })
    }

    @Test
    fun preservesCompleteComponentContractsAndChildFailure() {
        val emoji = ComponentEmoji("1", "wave", true)
        val option = SelectItem("One", "1", emoji, "First", true)
        val button = ButtonMessageComponent(
            ComponentType.BUTTON,
            3,
            ActionInteractionComponentState.Failed("nope"),
            "button-id",
            "Press",
            ButtonStyle.PRIMARY,
            emoji,
            null,
            true,
        )
        val select = SelectMessageComponent(
            ComponentType.SELECT,
            4,
            ActionInteractionComponentState.Enabled,
            "select-id",
            "Pick",
            1,
            2,
            listOf(option),
            listOf(option),
            false,
        )
        val row = ActionRowMessageComponent(ComponentType.ACTION_ROW, 2, listOf(button, select))

        assertEquals("nope", row.childError()?.errorMessage)
        assertEquals(option, select.selectedOptions.single())
        assertEquals(3, button.index)
        assertTrue(button.hasEmoji())
        assertFalse(button.hasIcon())
        assertTrue(button.copy(style = ButtonStyle.LINK).hasIcon())
        assertNull(ActionRowMessageComponent(ComponentType.ACTION_ROW, components = listOf(select)).childError())
    }

    @Test
    fun matchesButtonContentPaddingRules() {
        assertEquals(8 to 8, buttonLabelPaddingDp(true, true, true))
        assertEquals(16 to 16, buttonLabelPaddingDp(true, false, false))
        assertEquals(0 to 8, buttonLabelPaddingDp(false, true, true))
        assertEquals(0 to 16, buttonLabelPaddingDp(false, true, false))
    }
}
