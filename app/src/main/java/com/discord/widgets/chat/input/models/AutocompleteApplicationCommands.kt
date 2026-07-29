package com.discord.widgets.chat.input.models

import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.widgets.chat.input.WidgetChatInputDiscoveryCommandsModel

/** Command data available while reducing the current composer input. */
data class AutocompleteApplicationCommands(
    val applications: List<Application> = emptyList(),
    val queryCommands: List<ApplicationCommand> = emptyList(),
    val discoveryCommands: WidgetChatInputDiscoveryCommandsModel? = null,
)
