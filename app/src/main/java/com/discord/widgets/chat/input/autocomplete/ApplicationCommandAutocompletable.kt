package com.discord.widgets.chat.input.autocomplete

import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand

data class ApplicationCommandAutocompletable(
    val application: Application?,
    val command: ApplicationCommand,
    val hasPermissionToUse: Boolean,
    val showAvatar: Boolean,
) : Autocompletable() {
    private val textMatchers = listOf("${leadingIdentifier().identifier}${command.name}")

    override fun getInputReplacement() = "${leadingIdentifier().identifier}${command.name}"
    override fun getInputTextMatchers() = textMatchers
    override fun leadingIdentifier() = LeadingIdentifier.APP_COMMAND
}
