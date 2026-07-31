package com.discord.widgets.chat.input.models

import com.discord.models.commands.ApplicationCommandOption

sealed class InputSelectionModel private constructor(
    open val inputModel: MentionInputModel,
    open val selection: IntRange,
) {
    data class CommandInputSelectionModel(
        override val inputModel: MentionInputModel.VerifiedCommandInputModel,
        override val selection: IntRange,
        val selectedCommandOption: ApplicationCommandOption?,
    ) : InputSelectionModel(inputModel, selection)

    data class MessageInputSelectionModel(
        override val inputModel: MentionInputModel,
        override val selection: IntRange,
    ) : InputSelectionModel(inputModel, selection)
}
