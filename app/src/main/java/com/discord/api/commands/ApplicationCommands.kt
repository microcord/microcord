package com.discord.api.commands

import com.discord.api.application.Application

/**
 * A slash command advertised by a guild's applications.
 *
 * TODO(DTO-parity): the reference also carries `options: List<ApplicationCommandOption>` and
 * `permissions: List<ApplicationCommandPermission>`. Those are deep recursive option trees owned by
 * the application-commands wave; they are intentionally absent here rather than typed as `Any`, so
 * adding them later is a pure addition.
 */
data class ApplicationCommand(
    val id: Long,
    val applicationId: Long,
    val guildId: String?,
    val name: String,
    val description: String?,
    val version: String?,
    val defaultPermissions: Boolean?,
)

/** One autocomplete suggestion returned for an in-flight command argument. */
data class ApplicationCommandAutocompleteChoice(
    val name: String,
    val value: String,
)

/** Payload of `APPLICATION_COMMAND_AUTOCOMPLETE_RESPONSE`. */
data class ApplicationCommandAutocompleteResult(
    val nonce: String,
    val choices: List<ApplicationCommandAutocompleteChoice>,
)

/** Payload of `GUILD_APPLICATION_COMMANDS_UPDATE`, the response to an outgoing command query. */
data class GuildApplicationCommands(
    val guildId: Long,
    val updatedAt: Long,
    val applications: List<Application>,
    val nonce: String,
    val applicationCommands: List<ApplicationCommand>,
)
