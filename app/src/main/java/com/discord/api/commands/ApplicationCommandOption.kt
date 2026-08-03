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
    val description: String? = null,
    val required: Boolean = false,
    val default: Boolean = false,
    val channelTypes: List<Int>? = null,
    val choices: List<CommandChoice> = emptyList(),
    val options: List<ApplicationCommandOption>? = null,
    val autocomplete: Boolean = false,
    val minValue: Number? = null,
    val maxValue: Number? = null,
    val nameLocalizations: Map<String, String>? = null,
    val nameLocalized: String? = null,
    val descriptionLocalizations: Map<String, String>? = null,
    val descriptionLocalized: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val nameDefault: String? = null,
    val descriptionDefault: String? = null,
)
