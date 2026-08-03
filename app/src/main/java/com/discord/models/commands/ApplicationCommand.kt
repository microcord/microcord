package com.discord.models.commands

import com.discord.api.commands.ApplicationCommandPermission
import com.discord.api.commands.ApplicationCommandContext
import com.discord.api.commands.ApplicationCommandIndexPermissions
import com.discord.api.commands.ApplicationCommandIndexCommand

/**
 * A command the chat input can offer, whether it comes from an application ([RemoteApplicationCommand])
 * or from the client itself ([BuiltInCommand]).
 *
 * [permissions] is the flattened `id -> allowed` view of the API's permission overwrites; it is
 * consulted by [hasPermission] together with [defaultPermissions].
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
    val defaultPermissions: Boolean? = null,
    val permissions: Map<Long, Boolean>? = null,
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
    val handler: Int? = null,
    val apiCommand: ApplicationCommandIndexCommand? = null,
)

/**
 * Whether [userId], holding [roles], may invoke this command.
 *
 * A per-user overwrite wins outright; otherwise the first role overwrite that grants access wins;
 * otherwise the command's default applies. Commands without a default are unrestricted.
 */
fun ApplicationCommand.hasPermission(userId: Long, roles: List<Long>): Boolean {
    val defaultPermissions = defaultPermissions ?: return true
    val permissions = permissions
    if (permissions != null) {
        permissions[userId]?.let { return it }
        roles.map { permissions[it] }.firstOrNull { it == true }?.let { return it }
    }
    return defaultPermissions
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

fun ApplicationCommand.hasModernPermission(
    userId: Long,
    roles: List<Long>,
    memberPermissions: Long?,
    isGuildOwner: Boolean = false,
    channelId: Long? = null,
    guildId: Long? = null,
): Boolean {
    if (isGuildOwner || memberPermissions?.and(8L) == 8L) return true
    fun ApplicationCommandIndexPermissions?.resolve(): Boolean? {
        this ?: return null
        val channelPermission = channelId?.let { channels?.get(it) }
            ?: guildId?.let { channels?.get(it - 1L) }
        if (channelPermission == false) return false
        user?.let { return it }
        val roleValues = roles.mapNotNull { roleId -> this.roles?.get(roleId) }
        if (roleValues.any { it }) return true
        if (roleValues.isNotEmpty()) return false
        return null
    }
    commandPermissions.resolve()?.let { return it }
    applicationPermissions.resolve()?.let { return it }
    return defaultMemberPermissions?.let { required ->
        required != 0L && memberPermissions?.let { it and required == required } == true
    } ?: hasPermission(userId, roles)
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
    defaultPermissions: Boolean? = null,
    permissions: List<ApplicationCommandPermission>? = null,
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
    defaultPermissions = defaultPermissions,
    permissions = permissions?.associate { it.id to it.permission },
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
    handler = handler,
    apiCommand = apiCommand,
)

/** A command implemented locally by the client; it renders from resources and never hits the API. */
class BuiltInCommand(
    id: String,
    applicationId: Long,
    name: String,
    descriptionRes: Int? = null,
    options: List<ApplicationCommandOption>,
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
    defaultPermissions: Boolean? = null,
    permissions: List<ApplicationCommandPermission>? = null,
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
    defaultPermissions = defaultPermissions,
    permissions = permissions?.associate { it.id to it.permission },
    untranslatedName = untranslatedName,
    type = rootCommand.type,
    defaultMemberPermissions = rootCommand.defaultMemberPermissions,
    applicationPermissions = rootCommand.applicationPermissions,
    commandPermissions = rootCommand.commandPermissions,
    contexts = rootCommand.contexts,
    integrationTypes = rootCommand.integrationTypes,
    installationType = rootCommand.installationType,
    nsfw = rootCommand.nsfw,
    apiCommand = rootCommand.apiCommand,
)
