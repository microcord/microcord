package com.discord.api.commands

/** A fixed choice offered for an [ApplicationCommandOption]. */
data class CommandChoice(
    val name: String,
    val value: Any,
    val nameLocalizations: Map<String, String>? = null,
    val nameLocalized: String? = null,
) {
    val displayName: String get() = nameLocalized ?: name
}
