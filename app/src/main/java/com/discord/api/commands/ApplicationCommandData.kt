package com.discord.api.commands

/** The body of an interaction that invokes a slash command. */
data class ApplicationCommandData(
    val version: String?,
    val guildId: String?,
    val id: String,
    val name: String,
    val options: List<ApplicationCommandValue>?,
    val attachments: List<ApplicationCommandAttachment>?,
    val type: Int = ApplicationCommandIndexCommand.TYPE_CHAT_INPUT,
    val applicationCommand: ApplicationCommandIndexCommand? = null,
)
