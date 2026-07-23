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
import com.discord.widgets.botuikit.views.buttonLabelPaddingDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BotUiKitContractsTest {
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
