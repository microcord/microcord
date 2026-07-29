package com.discord.widgets.botuikit.views

import com.discord.api.botuikit.SelectItem

interface ComponentActionListener {
    fun onButtonComponentClick(componentIndex: Int, customId: String)

    fun onSelectComponentClick(
        componentIndex: Int,
        customId: String,
        placeholder: String?,
        options: List<SelectItem>,
        selectedItems: List<SelectItem>,
        minOptionsToSelect: Int,
        maxOptionsToSelect: Int,
        emojiAnimationsEnabled: Boolean,
    )
}
