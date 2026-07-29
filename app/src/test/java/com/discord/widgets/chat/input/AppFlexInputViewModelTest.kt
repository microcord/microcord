package com.discord.widgets.chat.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFlexInputViewModelTest {
    @Test
    fun expressionTrayTransitionsMatchThePublicFlexInputBridge() {
        val viewModel = AppFlexInputViewModel()
        val events = mutableListOf<AppFlexInputViewModel.Event>()
        viewModel.observeEvents().subscribe(events::add)

        assertTrue(viewModel.showExpressionTray())
        assertFalse(viewModel.showExpressionTray())
        assertTrue(viewModel.requireViewState().expressionTrayShown)
        assertEquals(listOf(AppFlexInputViewModel.Event.HideKeyboard), events)

        assertTrue(viewModel.hideExpressionTray())
        assertFalse(viewModel.hideExpressionTray())
        assertFalse(viewModel.requireViewState().expressionTrayShown)

        viewModel.showKeyboardAndHideExpressionTray()
        assertEquals(
            listOf(AppFlexInputViewModel.Event.HideKeyboard, AppFlexInputViewModel.Event.ShowKeyboard),
            events,
        )
    }
}
