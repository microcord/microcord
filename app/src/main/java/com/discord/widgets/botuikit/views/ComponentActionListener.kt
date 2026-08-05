package com.discord.widgets.botuikit.views

import com.discord.api.botuikit.ComponentType
import com.discord.api.botuikit.SelectItem
import com.discord.widgets.chat.list.entries.BotUiComponentEntry

interface ComponentActionListener {
    val componentEntry: BotUiComponentEntry? get() = null

    fun onButtonComponentClick(componentIndex: Int, customId: String)

    fun onSelectComponentClick(
        componentIndex: Int,
        componentType: ComponentType,
        customId: String,
        placeholder: String?,
        options: List<SelectItem>,
        selectedItems: List<SelectItem>,
        minOptionsToSelect: Int,
        maxOptionsToSelect: Int,
        emojiAnimationsEnabled: Boolean,
        defaultValues: List<String>,
    )
}
