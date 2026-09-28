package com.discord.api.commands

import com.discord.api.user.User

/** Modern command catalog returned for a guild, channel, or the current user's installed apps. */
data class ApplicationCommandIndex(
    val applications: List<ApplicationCommandIndexApplication> = emptyList(),
    val applicationCommands: List<ApplicationCommandIndexCommand> = emptyList(),
    val version: String? = null,
)

data class ApplicationCommandIndexPermissions(
    val user: Boolean? = null,
    val roles: Map<Long, Boolean>? = null,
    val channels: Map<Long, Boolean>? = null,
)

data class ApplicationCommandIndexApplication(
    val id: Long,
    val name: String,
    val description: String = "",
    val icon: String? = null,
    val permissions: ApplicationCommandIndexPermissions? = null,
    val bot: User? = null,
    val botId: Long? = null,
    val flags: String = "0",
    val embeddedActivityConfig: EmbeddedActivityConfig? = null,
) {
    data class EmbeddedActivityConfig(
        val supportedPlatforms: List<String>? = null,
    )
}

data class ApplicationCommandIndexCommand(
    val id: Long,
    val type: Int = TYPE_CHAT_INPUT,
    val applicationId: Long,
    val guildId: String? = null,
    val name: String,
    val nameDefault: String? = null,
    val description: String? = null,
    val descriptionDefault: String? = null,
    val options: List<ApplicationCommandOption>? = null,
    val defaultMemberPermissions: String? = null,
    val dmPermission: Boolean? = null,
    val permissions: ApplicationCommandIndexPermissions? = null,
    val nsfw: Boolean = false,
    val integrationTypes: List<Int>? = null,
    val globalPopularityRank: Int? = null,
    val contexts: List<Int>? = null,
    val version: String? = null,
    val handler: Int? = null,
) {
    companion object {
        const val TYPE_CHAT_INPUT = 1
        const val TYPE_USER = 2
        const val TYPE_MESSAGE = 3
        const val TYPE_PRIMARY_ENTRY_POINT = 4
    }
}

enum class ApplicationIntegrationType(val value: Int) {
    GUILD_INSTALL(0),
    USER_INSTALL(1),
}

enum class ApplicationCommandContext(val value: Int) {
    GUILD(0),
    BOT_DM(1),
    PRIVATE_CHANNEL(2),
}

data class GuildApplicationCommandIndexUpdate(val guildId: Long)
