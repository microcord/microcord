package com.discord.widgets.chat.input.autocomplete

import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ApplicationCommandOption

sealed class SelectedCommandViewState {
    object Hidden : SelectedCommandViewState()

    data class SelectedCommand(
        val command: ApplicationCommand,
        val option: ApplicationCommandOption?,
        val application: Application,
    ) : SelectedCommandViewState()
}
