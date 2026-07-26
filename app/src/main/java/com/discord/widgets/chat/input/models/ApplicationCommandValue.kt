package com.discord.widgets.chat.input.models

/**
 * A command argument as the composer holds it, before it is projected onto the wire type
 * [com.discord.api.commands.ApplicationCommandValue].
 *
 * The two look alike on purpose but are not interchangeable: this one is edited in place while the
 * user types, and [focused] tracks which argument the caret is in so autocomplete knows what to ask
 * about. [value] is untyped because the wire value depends on [type] — string, number, boolean or
 * snowflake.
 *
 * `SUBCOMMAND` and `SUBCOMMAND_GROUP` values nest their arguments in [options].
 */
data class ApplicationCommandValue(
    val name: String,
    val value: Any? = null,
    val type: Int,
    val options: List<ApplicationCommandValue>? = null,
    val focused: Boolean? = null,
)
