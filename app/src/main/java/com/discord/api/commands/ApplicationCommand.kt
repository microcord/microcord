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
    val type: Int = 1,
    val nameLocalizations: Map<String, String>? = null,
    val nameLocalized: String? = null,
    val descriptionLocalizations: Map<String, String>? = null,
    val descriptionLocalized: String? = null,
    val defaultMemberPermissions: String? = null,
    val dmPermission: Boolean? = null,
    val nsfw: Boolean = false,
    val integrationTypes: List<Int>? = null,
    val globalPopularityRank: Int? = null,
    val contexts: List<Int>? = null,
    val handler: Int? = null,
)
