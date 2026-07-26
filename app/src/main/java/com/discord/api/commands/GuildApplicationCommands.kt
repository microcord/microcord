package com.discord.api.commands

/**
 * Payload of `GUILD_APPLICATION_COMMANDS_UPDATE`, the response to an outgoing
 * `REQUEST_GUILD_APPLICATION_COMMANDS`.
 */
class GuildApplicationCommands(
    val guildId: Long,
    val updatedAt: Long,
    val applications: List<Application>,
    val nonce: String,
    val applicationCommands: List<ApplicationCommand>,
)
