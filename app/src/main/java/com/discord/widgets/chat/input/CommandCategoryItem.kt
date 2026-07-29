package com.discord.widgets.chat.input

import com.discord.models.commands.Application

data class CommandCategoryItem(
    val application: Application,
    val isSelected: Boolean,
)
