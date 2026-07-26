package com.discord.widgets.botuikit.views.select

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.botuikit.SelectItem

/**
 * The select sheet's list.
 *
 * Three list-wide facts are computed once per [setItems] and handed to every row, because they
 * decide the row's geometry and a row cannot see its siblings: whether *any* option has an emoji,
 * whether *any* has a description, and whether the selection cap has been reached.
 */
class SelectComponentBottomSheetAdapter(
    private val onItemSelected: (SelectItem, Boolean) -> Unit,
) : RecyclerView.Adapter<SelectComponentBottomSheetItemViewHolder>() {

    private var data: List<SelectComponentBottomSheetItem> = emptyList()
    private var hasIcons: Boolean = false
    private var hasDescriptions: Boolean = false
    private var isMultiSelect: Boolean = false
    private var isMaxSelected: Boolean = false
    private var emojiAnimationsEnabled: Boolean = true

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = data.size

    /**
     * Position *is* the identity here: the option list comes from the message and never reorders,
     * so a stable id keyed on position lets a selection toggle animate in place rather than
     * rebinding the row.
     */
    override fun getItemId(position: Int): Long = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SelectComponentBottomSheetItemViewHolder =
        SelectComponentBottomSheetItemViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.widget_select_component_bottom_sheet_item, parent, false),
            onItemSelected,
        )

    override fun onBindViewHolder(holder: SelectComponentBottomSheetItemViewHolder, position: Int) {
        holder.bind(
            data = data[position],
            isMultiSelect = isMultiSelect,
            hasIcons = hasIcons,
            hasDescriptions = hasDescriptions,
            lastItem = position == data.size - 1,
            maxSelected = isMaxSelected,
            emojiAnimationsEnabled = emojiAnimationsEnabled,
        )
    }

    fun setItems(
        items: List<SelectComponentBottomSheetItem>,
        maxSelections: Int,
        emojiAnimationsEnabled: Boolean,
    ) {
        data = items
        hasIcons = items.any { it.selectItem.emoji != null }
        hasDescriptions = items.any { it.selectItem.description != null }
        isMultiSelect = maxSelections > 1
        isMaxSelected = items.count { it.selected } == maxSelections
        this.emojiAnimationsEnabled = emojiAnimationsEnabled
        notifyDataSetChanged()
    }
}
