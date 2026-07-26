package com.discord.widgets.botuikit.views.select

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.botuikit.SelectItem
import com.discord.utilities.view.extensions.setEnabledAlpha
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.widgets.botuikit.views.setEmojiOrHide
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.checkbox.MaterialCheckBox

/**
 * One selectable option.
 *
 * The layout is uniform but the *metrics* are not: rows shift left when no option in the list has
 * an emoji, grow taller when any option has a description, and swap which of the two dividers is
 * drawn so the divider always starts under the text rather than under the icon column. All of that
 * is decided list-wide by the adapter and passed in, because a row cannot see its siblings.
 */
class SelectComponentBottomSheetItemViewHolder(
    itemView: View,
    private val onItemSelected: (SelectItem, Boolean) -> Unit,
) : RecyclerView.ViewHolder(itemView) {

    private val title: TextView = itemView.findViewById(R.id.select_component_sheet_item_title)
    private val description: TextView = itemView.findViewById(R.id.select_component_sheet_item_description)
    private val icon: SimpleDraweeView = itemView.findViewById(R.id.select_component_sheet_item_icon)
    private val checkbox: MaterialCheckBox = itemView.findViewById(R.id.select_component_sheet_item_selected)
    private val divider: View = itemView.findViewById(R.id.select_component_sheet_item_divider)
    private val dividerIcon: View = itemView.findViewById(R.id.select_component_sheet_item_divider_icon)

    fun bind(
        data: SelectComponentBottomSheetItem,
        isMultiSelect: Boolean,
        hasIcons: Boolean,
        hasDescriptions: Boolean,
        lastItem: Boolean,
        maxSelected: Boolean,
        emojiAnimationsEnabled: Boolean,
    ) {
        // Only unselected rows are locked out at the cap — the user must still be able to
        // *deselect* an option to make room for another.
        val locked = maxSelected && !data.selected && isMultiSelect
        val enabled = !locked

        itemView.isEnabled = enabled
        itemView.setEnabledAlpha(enabled, DISABLED_ALPHA)
        itemView.setOnClickListener { onItemSelected(data.selectItem, !data.selected) }

        title.text = data.selectItem.label
        description.setTextAndVisibilityBy(data.selectItem.description)

        checkbox.isChecked = data.selected
        // INVISIBLE, not GONE: the checkbox reserves its column so single- and multi-select rows
        // wrap their text identically.
        checkbox.visibility = if (isMultiSelect) View.VISIBLE else View.INVISIBLE
        checkbox.isEnabled = enabled

        icon.setEmojiOrHide(data.selectItem.emoji, emojiAnimationsEnabled)

        val verticalPadding = dp(12)
        val bottomPadding = if (data.selectItem.description == null) dp(12) else 0
        // A row with no emoji in an icon-bearing list is indented to the icon column instead.
        val startPadding = if (hasIcons && data.selectItem.emoji == null) dp(40) else 0
        title.setPadding(startPadding, verticalPadding, 0, bottomPadding)
        description.setPadding(startPadding, 0, 0, dp(12))

        dividerIcon.visibility = if (hasIcons && !lastItem) View.VISIBLE else View.GONE
        divider.visibility = if (!hasIcons && !lastItem) View.VISIBLE else View.GONE

        itemView.minimumHeight = dp(if (hasDescriptions) 62 else 46)
    }

    private fun dp(value: Int): Int =
        (value * itemView.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        private const val DISABLED_ALPHA = 0.3f
    }
}
