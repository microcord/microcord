package com.discord.widgets.botuikit.views.select

import androidx.annotation.MainThread
import com.discord.api.botuikit.SelectItem
import com.discord.app.AppViewModel
import rx.Observable
import rx.subjects.PublishSubject

/**
 * The select menu's selection state.
 *
 * Single- and multi-select are genuinely different interactions and the model treats them as such:
 * with `max == 1` a tap *is* the answer and the sheet closes immediately, while with `max > 1` taps
 * only accumulate and nothing is sent until the user presses Select. That is why [isMultiSelect]
 * drives both the checkbox visibility and the presence of the confirm button — one flag, one
 * meaning.
 *
 * Reference: `com.discord.widgets.botuikit.views.select.SelectComponentBottomSheetViewModel`. The
 * reference dispatches the interaction itself through `StoreApplicationInteractions` and records an
 * optimistic selection in `StoreLocalActionComponentState`; neither store exists in this project,
 * so the confirmed selection leaves through [onSelectionSubmitted] and the host performs the call.
 */
class SelectComponentBottomSheetViewModel(
    val componentContext: ComponentContext,
    val componentIndex: Int,
    val customId: String,
    val placeholder: String?,
    val items: List<SelectItem>,
    val min: Int,
    val max: Int,
    val selectedOptions: List<SelectItem>,
    val emojiAnimationsEnabled: Boolean,
    private val onSelectionSubmitted: (ComponentContext, componentIndex: Int, customId: String, values: List<String>) -> Unit,
) : AppViewModel<SelectComponentBottomSheetViewModel.ViewState>() {

    sealed class Event {
        object CloseSheet : Event()
    }

    data class ViewState(
        val title: String?,
        val items: List<SelectComponentBottomSheetItem>,
        val showSelectButton: Boolean,
        val isMultiSelect: Boolean,
        val minSelections: Int,
        val maxSelections: Int,
        val isValidSelection: Boolean,
        val emojiAnimationsEnabled: Boolean,
    )

    private val isMultiSelect: Boolean = max > 1
    private val selectedItems: MutableSet<SelectItem> = selectedOptions.toMutableSet()
    private val eventSubject: PublishSubject<Event> = PublishSubject.create()
    private var submitted = false

    init {
        updateViewState()
    }

    fun observeEvents(): Observable<Event> = eventSubject

    @MainThread
    fun selectItem(selectItem: SelectItem, selected: Boolean) {
        if (!isMultiSelect) {
            // A single-select menu has no confirm step: the tap is the answer.
            sendSelectInteraction(setOf(selectItem))
            return
        }
        if (selected) selectedItems.add(selectItem) else selectedItems.remove(selectItem)
        updateViewState()
    }

    fun onClickSelect() = sendSelectInteraction(selectedItems.toSet())

    fun onSheetDismissed() {
        if (isMultiSelect && isValidSelection() && !submitted) publishSelection(selectedItems)
    }

    /**
     * Both bounds are checked, not just the minimum: `max` is enforced by the row binder greying
     * out unselected rows once the cap is reached, but a component whose `default` options already
     * exceed `max` would otherwise arrive in an unsendable state with the button enabled.
     */
    private fun isValidSelection(): Boolean = selectedItems.size in min..max

    private fun sendSelectInteraction(selection: Set<SelectItem>) {
        if (submitted) return
        publishSelection(selection)
        eventSubject.onNext(Event.CloseSheet)
    }

    private fun publishSelection(selection: Set<SelectItem>) {
        submitted = true
        onSelectionSubmitted(componentContext, componentIndex, customId, selection.map(SelectItem::value))
    }

    private fun updateViewState() {
        updateViewState(
            ViewState(
                title = placeholder,
                items = items.map { SelectComponentBottomSheetItem(it, selectedItems.contains(it)) },
                showSelectButton = isMultiSelect,
                isMultiSelect = isMultiSelect,
                minSelections = min,
                maxSelections = max,
                isValidSelection = isValidSelection(),
                emojiAnimationsEnabled = emojiAnimationsEnabled,
            ),
        )
    }
}
