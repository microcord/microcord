package com.discord.widgets.botuikit.views.select

import com.discord.api.botuikit.SelectItem

/** One row of the select sheet: the option itself plus whether it is currently chosen. */
data class SelectComponentBottomSheetItem(
    val selectItem: SelectItem,
    val selected: Boolean,
)
