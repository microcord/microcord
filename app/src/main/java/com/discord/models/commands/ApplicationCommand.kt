package com.discord.models.commands

import com.discord.api.commands.ApplicationCommandContext
import com.discord.api.commands.ApplicationCommandIndexPermissions
import com.discord.api.commands.ApplicationCommandIndexCommand

/**
 * A command the chat input can offer, whether it comes from an application ([RemoteApplicationCommand])
 * or from the client itself ([BuiltInCommand]).
 */
sealed class ApplicationCommand(
    val id: String,
    val applicationId: Long,
    val name: String,
    val description: String? = null,
    val descriptionRes: Int? = null,
    val options: List<ApplicationCommandOption>,
    val builtIn: Boolean = false,
    val guildId: Long? = null,
    val version: String? = null,
    val execute: ((Map<String, Any?>) -> String)? = null,
    val untranslatedName: String = name,
    val type: Int = 1,
    val defaultMemberPermissions: Long? = null,
    val applicationPermissions: ApplicationCommandIndexPermissions? = null,
    val commandPermissions: ApplicationCommandIndexPermissions? = null,
    val contexts: Set<Int>? = null,
    val integrationTypes: Set<Int>? = null,
    val installationType: Int? = null,
    val nsfw: Boolean = false,
    val dmPermission: Boolean? = null,
    val globalPopularityRank: Int? = null,
    val handler: Int? = null,
    val apiCommand: ApplicationCommandIndexCommand? = null,
)

/**
 * The order an application's own commands are listed in.
 *
 * Alphabetical until the server ranks at least one of them, at which point rank wins and
 * alphabetical order only breaks ties.
 */
fun List<ApplicationCommand>.sortedForSection(): List<ApplicationCommand> {
    val alphabetical = sortedBy { it.name }
    if (alphabetical.none { it.globalPopularityRank != null }) return alphabetical
    return alphabetical.withIndex()
        .sortedWith(
            compareBy<IndexedValue<ApplicationCommand>, Int?>(nullsLast()) {
                it.value.globalPopularityRank
            }.thenBy { it.index },
        )
        .map(IndexedValue<ApplicationCommand>::value)
}

fun ApplicationCommand.isAvailableIn(guildId: Long, isBotDm: Boolean): Boolean {
    val context = when {
        guildId > 0L -> ApplicationCommandContext.GUILD.value
        isBotDm -> ApplicationCommandContext.BOT_DM.value
        else -> ApplicationCommandContext.PRIVATE_CHANNEL.value
    }
    if (contexts != null && context !in contexts) return false
    return installationType == null || integrationTypes.isNullOrEmpty() || installationType in integrationTypes
}

/** A command supplied by an application and advertised over the gateway or REST. */
class RemoteApplicationCommand(
    id: String,
    applicationId: Long,
    name: String,
    description: String? = null,
    options: List<ApplicationCommandOption>,
    guildId: Long?,
    version: String?,
    execute: ((Map<String, Any?>) -> String)? = null,
    untranslatedName: String = name,
    type: Int = 1,
    defaultMemberPermissions: Long? = null,
    applicationPermissions: ApplicationCommandIndexPermissions? = null,
    commandPermissions: ApplicationCommandIndexPermissions? = null,
    contexts: Set<Int>? = null,
    integrationTypes: Set<Int>? = null,
    installationType: Int? = null,
    nsfw: Boolean = false,
    dmPermission: Boolean? = null,
    globalPopularityRank: Int? = null,
    handler: Int? = null,
    apiCommand: ApplicationCommandIndexCommand? = null,
) : ApplicationCommand(
    id = id,
    applicationId = applicationId,
    name = name,
    description = description,
    options = options,
    guildId = guildId,
    version = version,
    execute = execute,
    untranslatedName = untranslatedName,
    type = type,
    defaultMemberPermissions = defaultMemberPermissions,
    applicationPermissions = applicationPermissions,
    commandPermissions = commandPermissions,
    contexts = contexts,
    integrationTypes = integrationTypes,
    installationType = installationType,
    nsfw = nsfw,
    dmPermission = dmPermission,
    globalPopularityRank = globalPopularityRank,
    handler = handler,
    apiCommand = apiCommand,
)

/** What a built-in command does to the client instead of producing message text. */
enum class BuiltInCommandAction { LEAVE_GROUP_DM }

/** A command implemented locally by the client; it renders from resources and never hits the API. */
class BuiltInCommand(
    id: String,
    applicationId: Long,
    name: String,
    descriptionRes: Int? = null,
    options: List<ApplicationCommandOption>,
    val action: BuiltInCommandAction? = null,
    // Trails the parameter list so callers can keep passing it as a lambda literal.
    execute: ((Map<String, Any?>) -> String)? = null,
) : ApplicationCommand(
    id = id,
    applicationId = applicationId,
    name = name,
    descriptionRes = descriptionRes,
    options = options,
    builtIn = true,
    execute = execute,
)

/**
 * One leaf of a command that declares sub-commands.
 *
 * The id is synthesised from the root command's id plus the (optional) group and leaf names
 * separated by spaces, so `/role add` and `/role remove` remain distinguishable in the picker.
 */
class ApplicationSubCommand(
    val rootCommand: ApplicationCommand,
    val subCommandName: String,
    val parentGroupName: String? = null,
    applicationId: Long,
    name: String,
    description: String? = null,
    descriptionRes: Int? = null,
    options: List<ApplicationCommandOption>,
    guildId: Long?,
    version: String?,
    untranslatedName: String = name,
) : ApplicationCommand(
    id = if (parentGroupName != null) {
        "${rootCommand.id} $parentGroupName $subCommandName"
    } else {
        "${rootCommand.id} $subCommandName"
    },
    applicationId = applicationId,
    name = name,
    description = description,
    descriptionRes = descriptionRes,
    options = options,
    guildId = guildId,
    version = version,
    untranslatedName = untranslatedName,
    type = rootCommand.type,
    defaultMemberPermissions = rootCommand.defaultMemberPermissions,
    applicationPermissions = rootCommand.applicationPermissions,
    commandPermissions = rootCommand.commandPermissions,
    contexts = rootCommand.contexts,
    integrationTypes = rootCommand.integrationTypes,
    installationType = rootCommand.installationType,
    nsfw = rootCommand.nsfw,
    dmPermission = rootCommand.dmPermission,
    globalPopularityRank = rootCommand.globalPopularityRank,
    apiCommand = rootCommand.apiCommand,
)
