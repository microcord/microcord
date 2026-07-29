package com.discord.widgets.chat.input.autocomplete.commands

import com.discord.api.commands.ApplicationCommandType
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ApplicationCommandOption
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
        val choice = option.choices?.firstOrNull { it.name.trim() == raw }?.value
            ?: (matched as? ApplicationCommandChoiceAutocompletable)?.choice?.value
        ApplicationCommandValue(
            name = option.name,
            value = choice ?: when (option.type) {
                ApplicationCommandType.INTEGER -> raw.toLongOrNull() ?: raw
                ApplicationCommandType.BOOLEAN -> raw.toBooleanStrictOrNull() ?: raw
                ApplicationCommandType.NUMBER -> raw.toDoubleOrNull() ?: raw
                ApplicationCommandType.USER -> (matched as? UserAutocompletable)?.user?.id ?: raw
                ApplicationCommandType.CHANNEL -> (matched as? ChannelAutocompletable)?.channel?.id ?: raw
                ApplicationCommandType.ROLE -> (matched as? RoleAutocompletable)?.role?.id ?: raw
                ApplicationCommandType.MENTIONABLE -> when (matched) {
                    is UserAutocompletable -> matched.user.id
                    is RoleAutocompletable -> matched.role.id
                    else -> raw
                }
                else -> raw
            },
            type = option.type.type,
            focused = option == focusedOption,
        )
    }
    return ApplicationCommandData(application, command, values)
}
