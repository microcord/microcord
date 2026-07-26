package com.discord.api.commands

/**
 * One argument of an [ApplicationCommand].
 *
 * [options] is recursive: `SUBCOMMAND` and `SUBCOMMAND_GROUP` options carry their own argument
 * lists.
 */
data class ApplicationCommandOption(
    val type: ApplicationCommandType,
    val name: String,
    val description: String?,
    val required: Boolean,
    val default: Boolean,
    val channelTypes: List<Int>?,
    val choices: List<CommandChoice>,
    val options: List<ApplicationCommandOption>?,
    val autocomplete: Boolean,
    val minValue: Number?,
    val maxValue: Number?,
)
