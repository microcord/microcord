package com.discord.widgets.chat.input.models

import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand

data class InputCommandContext(
    val selectedApplication: Application? = null,
    val selectedCommand: ApplicationCommand? = null,
) {
    val isCommand: Boolean = selectedCommand != null
}
