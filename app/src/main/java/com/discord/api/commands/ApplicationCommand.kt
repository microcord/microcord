package com.discord.api.commands

/** A slash command advertised by an application, optionally scoped to one guild. */
data class ApplicationCommand(
    val id: Long,
    val applicationId: Long,
    val name: String,
    val description: String?,
    val options: List<ApplicationCommandOption>?,
    val version: String?,
    val guildId: String?,
    val defaultPermissions: Boolean?,
    val permissions: List<ApplicationCommandPermission>?,
)
