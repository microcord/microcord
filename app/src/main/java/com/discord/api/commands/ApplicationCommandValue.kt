package com.discord.api.commands

/**
 * A filled-in command argument on its way to the server.
 *
 * [value] is untyped because the wire value depends on [type]: a string, a number, a boolean or a
 * snowflake. The reference declares it as `Object` for the same reason.
 */
data class ApplicationCommandValue(
    val name: String,
    val value: Any?,
    val type: Int,
    val options: List<ApplicationCommandValue>?,
    val focused: Boolean?,
)
