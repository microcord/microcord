package com.discord.api.commands

/** One suggestion offered for an in-flight command argument. */
data class ApplicationCommandAutocompleteChoice(
    val name: String,
    val value: Any,
    val nameLocalized: String? = null,
) {
    val displayName: String get() = nameLocalized ?: name
}

/**
 * Payload of `APPLICATION_COMMAND_AUTOCOMPLETE_RESPONSE`.
 *
 * [nonce] correlates the response with the request that asked for it, so a stale response for an
 * argument the user has already moved past can be dropped.
 */
data class ApplicationCommandAutocompleteResult(
    val nonce: String,
    val choices: List<ApplicationCommandAutocompleteChoice>,
)
