package com.discord.widgets.chat.input.autocomplete

import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.widgets.chat.input.WidgetChatInputDiscoveryCommandsModel

/** Full command-browser state; the existing popup consumes [discoverCommands]' flattened rows. */
data class CommandBrowserState(
    val frequentCommands: List<ApplicationCommand>,
    val applications: List<Application>,
    val discoverCommands: WidgetChatInputDiscoveryCommandsModel,
)
