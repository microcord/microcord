package com.discord.widgets.chat.input.autocomplete.commands

import com.discord.api.commands.ApplicationCommandType
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ApplicationCommandOption
import com.discord.models.commands.ApplicationSubCommand
import com.discord.widgets.chat.input.autocomplete.ApplicationCommandChoiceAutocompletable
import com.discord.widgets.chat.input.autocomplete.ChannelAutocompletable
import com.discord.widgets.chat.input.autocomplete.RoleAutocompletable
import com.discord.widgets.chat.input.autocomplete.UserAutocompletable
import com.discord.widgets.chat.input.autocomplete.Autocompletable
import com.discord.widgets.chat.input.autocomplete.AutocompleteModelUtils
import com.discord.widgets.chat.input.models.ApplicationCommandData
import com.discord.widgets.chat.input.models.ApplicationCommandValue

/** Builds the focused command payload used by remote option autocomplete. */
fun buildRemoteCommandOptionRequest(
    input: CharSequence,
    application: Application,
    command: ApplicationCommand,
    focusedOption: ApplicationCommandOption,
    mentions: Collection<Autocompletable>,
): ApplicationCommandData {
    val values = command.options.mapNotNull { option ->
        val range = AutocompleteModelUtils.findCommandOptionValueRange(input, option, command.options)
        if (range == null && option != focusedOption) return@mapNotNull null
        val raw = range?.let { input.subSequence(it.first, it.last.coerceAtMost(input.length)).toString().trim() }.orEmpty()
        val matched = mentions.firstOrNull { it.matchesText(raw) }
        val choice = option.choices?.firstOrNull {
            it.displayName.trim() == raw || it.name.trim() == raw
        }?.value
            ?: (matched as? ApplicationCommandChoiceAutocompletable)?.choice?.value
        val value = choice ?: when (option.type) {
            ApplicationCommandType.INTEGER -> raw.toLongOrNull() ?: raw
            ApplicationCommandType.BOOLEAN -> raw.toBooleanStrictOrNull() ?: raw
            ApplicationCommandType.NUMBER -> raw.toDoubleOrNull() ?: raw
            ApplicationCommandType.USER -> (matched as? UserAutocompletable)?.user?.id?.toString() ?: raw
            ApplicationCommandType.CHANNEL -> (matched as? ChannelAutocompletable)?.channel?.id?.toString() ?: raw
            ApplicationCommandType.ROLE -> (matched as? RoleAutocompletable)?.role?.id?.toString() ?: raw
            ApplicationCommandType.MENTIONABLE -> when (matched) {
                is UserAutocompletable -> matched.user.id.toString()
                is RoleAutocompletable -> matched.role.id.toString()
                else -> raw
            }
            else -> raw
        }
        ApplicationCommandValue(
            name = option.untranslatedName,
            value = value,
            type = option.type.type,
            focused = true.takeIf { option == focusedOption },
        )
    }
    val root = (command as? ApplicationSubCommand)?.rootCommand ?: command
    val nestedValues = if (command is ApplicationSubCommand) {
        val group = command.parentGroupName?.let { name -> root.options.firstOrNull { it.name == name } }
        val subcommand = (group?.options ?: root.options).firstOrNull { it.name == command.subCommandName }
        val subcommandValue = ApplicationCommandValue(
            name = subcommand?.untranslatedName ?: command.subCommandName,
            type = ApplicationCommandType.SUBCOMMAND.type,
            options = values,
        )
        if (group == null) listOf(subcommandValue) else listOf(
            ApplicationCommandValue(
                name = group.untranslatedName,
                type = ApplicationCommandType.SUBCOMMAND_GROUP.type,
                options = listOf(subcommandValue),
            ),
        )
    } else values
    return ApplicationCommandData(application, root, nestedValues)
}
