package com.discord.views

import android.widget.Checkable

/**
 * Coordinates a set of independent [Checkable] rows so that at most one of them is checked,
 * giving `CheckedSetting`, `RadioButton` and friends radio-group semantics without requiring
 * them to share a `RadioGroup` parent.
 *
 * Screens build one per logical group (channel/server notification overrides, channel type
 * pickers, integration expiry behaviour and grace period, subscription plan format) and call
 * [setChecked] from each row's click listener.
 *
 * Construction normalizes the group: every button is unchecked, and the first button is checked
 * only when nothing in the group was checked to begin with. This mirrors the reference exactly —
 * a group that already had a selection ends up with none until the owning screen applies the
 * selection derived from its model, which every reference caller does immediately after
 * constructing the manager.
 */
class RadioManager(private val buttons: List<Checkable>) {

    init {
        var hasCheckedButton = false
        buttons.forEach { button ->
            hasCheckedButton = hasCheckedButton || button.isChecked
            button.isChecked = false
        }
        if (!hasCheckedButton) {
            buttons.first().isChecked = true
        }
    }

    /** Index of the checked button in the group, or `-1` when nothing is checked. */
    val checkedButtonIndex: Int
        get() = buttons.indexOfFirst { it.isChecked }

    /**
     * Checks [targetButton] and unchecks every other button in the group. A button that is not
     * part of the group simply clears the whole group.
     */
    fun setChecked(targetButton: Checkable) {
        buttons.forEach { button -> button.isChecked = button == targetButton }
    }
}
