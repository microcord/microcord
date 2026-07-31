@file:JvmName("InputSelectionModelKt")

package com.discord.widgets.chat.input.models

import com.discord.api.commands.ApplicationCommandType
import com.discord.models.commands.ApplicationCommand

fun InputSelectionModel.getSelectedCommand(): ApplicationCommand? =
    (inputModel as? MentionInputModel.VerifiedCommandInputModel)?.inputCommandContext?.selectedCommand

fun InputSelectionModel.hasSelectedCommandOptionWithChoices(): Boolean =
    this is InputSelectionModel.CommandInputSelectionModel &&
        (!selectedCommandOption?.choices.isNullOrEmpty() || selectedCommandOption?.type == ApplicationCommandType.BOOLEAN)

fun InputSelectionModel.hasSelectedFreeformInput(): Boolean = when (this) {
    is InputSelectionModel.MessageInputSelectionModel -> true
    is InputSelectionModel.CommandInputSelectionModel -> selectedCommandOption?.let { option ->
        option.choices.isNullOrEmpty() &&
            !option.autocomplete &&
            (option.type == ApplicationCommandType.INTEGER || option.type == ApplicationCommandType.STRING)
    } ?: true
}
