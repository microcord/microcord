package com.discord.widgets.botuikit

import com.discord.api.botuikit.SelectItem
import com.discord.widgets.botuikit.views.select.ComponentContext
import com.discord.widgets.botuikit.views.select.SelectComponentBottomSheetViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectComponentBottomSheetViewModelTest {

    private val one = SelectItem("One", "1", null, null, false)
    private val two = SelectItem("Two", "2", null, null, false)
    private val three = SelectItem("Three", "3", null, null, false)

    private val context = ComponentContext(
        guildId = 7L,
        messageId = 100L,
        channelId = 200L,
        messageFlags = 64L,
        applicationId = 300L,
    )

    @Test
    fun singleSelectSendsOnTapAndClosesWithoutAConfirmButton() {
        val submitted = mutableListOf<List<String>>()
        val viewModel = viewModel(min = 1, max = 1, onSubmit = { submitted += it })
        val events = mutableListOf<SelectComponentBottomSheetViewModel.Event>()
        viewModel.observeEvents().subscribe { events += it }

        assertFalse(viewModel.requireViewState().showSelectButton)
        viewModel.selectItem(two, true)

        assertEquals(listOf(listOf("2")), submitted)
        assertEquals(listOf(SelectComponentBottomSheetViewModel.Event.CloseSheet), events)
    }

    @Test
    fun multiSelectAccumulatesAndOnlySendsOnConfirm() {
        val submitted = mutableListOf<List<String>>()
        val viewModel = viewModel(min = 2, max = 3, onSubmit = { submitted += it })

        assertTrue(viewModel.requireViewState().showSelectButton)
        // Below the minimum, so the confirm button is inert.
        assertFalse(viewModel.requireViewState().isValidSelection)

        viewModel.selectItem(one, true)
        assertTrue(submitted.isEmpty())
        assertFalse(viewModel.requireViewState().isValidSelection)

        viewModel.selectItem(three, true)
        assertTrue(viewModel.requireViewState().isValidSelection)

        viewModel.onClickSelect()
        assertEquals(listOf(listOf("1", "3")), submitted)
    }

    @Test
    fun deselectingBelowTheMinimumInvalidatesTheSelectionAgain() {
        val viewModel = viewModel(min = 2, max = 3, selected = listOf(one, two))
        assertTrue(viewModel.requireViewState().isValidSelection)

        viewModel.selectItem(one, false)
        assertFalse(viewModel.requireViewState().isValidSelection)
        assertEquals(
            listOf(false, true, false),
            viewModel.requireViewState().items.map { it.selected },
        )
    }

    @Test
    fun defaultsArriveSelectedAndAreCarriedIntoTheViewState() {
        val viewModel = viewModel(min = 1, max = 2, selected = listOf(two))

        assertEquals(
            listOf(false, true, false),
            viewModel.requireViewState().items.map { it.selected },
        )
        assertEquals(1, viewModel.requireViewState().minSelections)
        assertEquals(2, viewModel.requireViewState().maxSelections)
    }

    private fun viewModel(
        min: Int,
        max: Int,
        selected: List<SelectItem> = emptyList(),
        onSubmit: (List<String>) -> Unit = {},
    ) = SelectComponentBottomSheetViewModel(
        componentContext = context,
        componentIndex = 2,
        customId = "pick-one",
        placeholder = "Pick",
        items = listOf(one, two, three),
        min = min,
        max = max,
        selectedOptions = selected,
        emojiAnimationsEnabled = true,
        onSelectionSubmitted = { _, _, _, values -> onSubmit(values) },
    )
}
